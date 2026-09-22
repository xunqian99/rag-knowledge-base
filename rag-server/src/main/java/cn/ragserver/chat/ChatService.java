package cn.ragserver.chat;

import cn.ragserver.cache.CachedAnswer;
import cn.ragserver.cache.QaCacheService;
import cn.ragserver.common.BusinessException;
import cn.ragserver.retrieval.RetrievedChunk;
import cn.ragserver.retrieval.RetrievalPipeline;
import cn.ragserver.retrieval.RetrievalResult;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * 问答主流程:检索 -> 组装 -> 生成 -> 落库。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 检索不到任何资料时的固定回复 */
    private static final String NO_CONTENT_ANSWER = "资料中没有找到相关内容。";

    private final ChatModel chatModel;
    private final ChatProperties properties;
    private final RetrievalPipeline retrievalPipeline;
    private final PromptBuilder promptBuilder;
    private final ChatHistoryService historyService;
    private final CitationBuilder citationBuilder;
    private final QaCacheService cacheService;
    private final FallbackAnswerBuilder fallbackAnswerBuilder;
    private final DegradationAssembler degradationAssembler;

    public ChatService(ChatModel chatModel,
                       ChatProperties properties,
                       RetrievalPipeline retrievalPipeline,
                       PromptBuilder promptBuilder,
                       ChatHistoryService historyService,
                       CitationBuilder citationBuilder,
                       QaCacheService cacheService,
                       FallbackAnswerBuilder fallbackAnswerBuilder,
                       DegradationAssembler degradationAssembler) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.retrievalPipeline = retrievalPipeline;
        this.promptBuilder = promptBuilder;
        this.historyService = historyService;
        this.citationBuilder = citationBuilder;
        this.cacheService = cacheService;
        this.fallbackAnswerBuilder = fallbackAnswerBuilder;
        this.degradationAssembler = degradationAssembler;
    }

    public AnswerResponse ask(QuestionRequest request) {
        long start = System.nanoTime();

        String question = request == null ? null : request.question();
        if (!StringUtils.hasText(question)) {
            throw new BusinessException("QUESTION_EMPTY", "问题不能为空");
        }
        question = question.trim();

        ensureConfigured();

        // 0. 先查缓存。命中就完全不用走检索和模型调用 ——
        //    一次问答要三次外部 API 调用、耗时 4 秒,命中缓存是几毫秒、零成本。
        Optional<CachedAnswer> cached = cacheService.get(question);
        if (cached.isPresent()) {
            CachedAnswer hit = cached.get();
            long costMs = (System.nanoTime() - start) / 1_000_000;
            // 命中缓存也要记一条会话历史,否则用户会发现"这次提问没有出现在记录里"
            Long sessionId = historyService.record(
                    request.sessionId(), question, hit.answer(), citedChunkIds(hit), (int) costMs);
            log.info("命中问答缓存,耗时 {}ms", costMs);
            // 降级产物本来就不会写进缓存,所以命中缓存时清单一定是空的
            return AnswerResponse.of(sessionId, hit.answer(), hit.citations(), costMs, List.of());
        }

        // 1. 完整检索链路:两路召回 -> RRF 融合 -> 精排。
        //    这三步都封装在 RetrievalPipeline 里,和调试接口共用同一套代码 ——
        //    否则调参时容易只改了其中一处,两边结果对不上。
        RetrievalResult retrieval = retrievalPipeline.retrieve(
                question, Math.max(1, properties.getTopK()));
        List<RetrievedChunk> chunks = retrieval.chunks();
        // 大模型是否降级。检索阶段的降级(召回通道失败、精排失败)由 retrieval 自己携带,
        // 最后统一交给 DegradationAssembler 汇总 —— 这里不用手动合并。
        boolean chatModelFailed = false;

        // 3. 生成。检索为空时直接给出固定回复,不浪费一次模型调用 ——
        //    反正 prompt 里没有任何资料,模型也只能说不知道。
        String answer;
        if (chunks.isEmpty()) {
            answer = NO_CONTENT_ANSWER;
        } else {
            try {
                ChatResponse response = chatModel.chat(
                        SystemMessage.from(promptBuilder.systemPrompt()),
                        UserMessage.from(promptBuilder.userPrompt(question, chunks)));
                answer = response.aiMessage().text();
            } catch (Exception ex) {
                // 【降级】大模型不可用时,把检索到的资料原样返回。
                //
                // 用户拿到的不是答案,而是"相关内容清单" —— 需要自己读一遍。
                // 体验确实差,但比一个错误页有价值得多:检索部分已经成功,
                // 只是最后一步生成失败了,没道理把前面的工作全丢掉。
                chatModelFailed = true;
                log.warn("大模型不可用,降级为返回检索到的原文片段:{}", ex.getMessage());
                answer = fallbackAnswerBuilder.build(chunks);
            }
        }

        // 汇总本次请求踩到的所有降级。空清单 = 完整链路跑完,没有任何环节降级。
        List<AnswerResponse.Degradation> degradations =
                degradationAssembler.from(retrieval, chatModelFailed);

        // 4. 组装候选引用。编号必须和 prompt 里的 [1][2] 严格对应。
        //    这套逻辑和流式链路共用 CitationBuilder,避免两边判断不一致 ——
        //    同一个问题在两种调用方式下给出不同的引用,是很难解释的体验。
        List<AnswerResponse.Citation> candidates = citationBuilder.buildCandidates(chunks);

        // 5. 只保留答案真正引用到的那些
        List<AnswerResponse.Citation> citations = citationBuilder.keepCitedOnly(candidates, answer);
        Long[] citedChunkIds = citations.stream()
                .map(AnswerResponse.Citation::chunkId)
                .toArray(Long[]::new);

        long costMs = (System.nanoTime() - start) / 1_000_000;

        // 6. 落库
        Long sessionId = historyService.record(
                request.sessionId(), question, answer, citedChunkIds, (int) costMs);

        // 7. 写入缓存。
        //
        // 【降级结果绝不写缓存】
        //
        // 这是踩过的坑:模型临时不可用时会降级返回"资料清单",
        // 如果把这个结果缓存起来,那么即使模型几十秒后就恢复了,
        // 用户在接下来整整一个 TTL 周期内拿到的**都还是那份降级产物** ——
        // 而且从缓存里读出来时 degraded 标记已经丢了,前端也不知道该提示。
        //
        // 缓存的前提是"这份结果在一段时间内是正确的"。
        // 降级产物恰恰不满足这个前提,它的正确性取决于一个临时故障何时恢复。
        if (degradations.isEmpty()) {
            cacheService.put(question, answer, citations);
        } else {
            log.warn("本次结果是降级产物,不写入缓存:{}",
                    degradations.stream().map(AnswerResponse.Degradation::code).toList());
        }

        return AnswerResponse.of(sessionId, answer, citations, costMs, degradations);
    }

    private static Long[] citedChunkIds(CachedAnswer cached) {
        return cached.citations().stream()
                .map(AnswerResponse.Citation::chunkId)
                .toArray(Long[]::new);
    }

    private void ensureConfigured() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BusinessException("CHAT_NOT_CONFIGURED",
                    "未配置对话模型密钥。请在 application-local.yml 里填写 rag.embedding.api-key,"
                            + "并以 local 配置启动应用。");
        }
    }

}
