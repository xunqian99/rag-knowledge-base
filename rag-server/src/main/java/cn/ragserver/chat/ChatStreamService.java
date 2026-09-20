package cn.ragserver.chat;

import cn.ragserver.cache.CachedAnswer;
import cn.ragserver.cache.QaCacheService;
import cn.ragserver.common.BusinessException;
import cn.ragserver.config.ThreadPoolConfig;
import cn.ragserver.retrieval.RetrievalPipeline;
import cn.ragserver.retrieval.RetrievedChunk;
import cn.ragserver.retrieval.RetrievalResult;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 流式问答。
 *
 * 【和非流式的区别】
 *
 * 非流式要等模型把整段答案生成完才返回,用户盯着空白等 3~5 秒;
 * 流式则是一边生成一边往回推片段,用户几百毫秒就能看到第一个字。
 *
 * **总耗时其实差不多,但感知完全不同。** 这也是为什么首字延迟(TTFT)
 * 比总耗时更值得作为前端体验的指标。
 *
 * 【为什么检索阶段也要计入 TTFT】
 *
 * 完整的首字延迟 = 检索耗时 + 模型出第一个字的耗时。
 * 检索那一段(向量化 + 召回 + 精排)要一两秒,是延迟的大头之一,
 * 所以这里把两段分别上报,便于定位瓶颈在哪。
 */
@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    /** SSE 连接本身的超时。设得比模型超时长,免得连接先断。 */
    private static final long EMITTER_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);

    /** 等待模型把答案生成完的上限 */
    private static final int STREAM_TIMEOUT_SECONDS = 120;

    private final StreamingChatModel streamingChatModel;
    private final ChatProperties properties;
    private final RetrievalPipeline retrievalPipeline;
    private final PromptBuilder promptBuilder;
    private final ChatHistoryService historyService;
    private final CitationBuilder citationBuilder;
    private final QaCacheService cacheService;
    private final FallbackAnswerBuilder fallbackAnswerBuilder;

    /**
     * 专门跑流式生成的线程池,由 ThreadPoolConfig 统一配置。
     *
     * 必须异步:SSE 的做法是「立刻把 emitter 返回给容器,再在别的线程里慢慢往里推数据」。
     * 如果直接在请求线程里生成,那就退化成同步请求了 —— 只不过响应体是分块的而已。
     *
     * 线程数、队列容量、拒绝策略都在 application.yml 的 rag.thread-pool 里。
     */
    private final ThreadPoolTaskExecutor executor;

    public ChatStreamService(StreamingChatModel streamingChatModel,
                             ChatProperties properties,
                             RetrievalPipeline retrievalPipeline,
                             PromptBuilder promptBuilder,
                             ChatHistoryService historyService,
                             CitationBuilder citationBuilder,
                             QaCacheService cacheService,
                             FallbackAnswerBuilder fallbackAnswerBuilder,
                             @Qualifier(ThreadPoolConfig.CHAT_STREAM_EXECUTOR)
                             ThreadPoolTaskExecutor executor) {
        this.streamingChatModel = streamingChatModel;
        this.properties = properties;
        this.retrievalPipeline = retrievalPipeline;
        this.promptBuilder = promptBuilder;
        this.historyService = historyService;
        this.citationBuilder = citationBuilder;
        this.cacheService = cacheService;
        this.fallbackAnswerBuilder = fallbackAnswerBuilder;
        this.executor = executor;
    }

    /**
     * 开启一次流式问答。
     *
     * 方法本身立刻返回 emitter,真正的生成在后台线程里进行。
     * 所以参数校验必须在这里同步做掉 —— 否则「问题为空」这种错误只能以 SSE 事件的形式返回,
     * 而调用方往往期望的是一个 400 状态码。
     */
    public SseEmitter stream(QuestionRequest request) {
        String question = request == null ? null : request.question();
        if (!StringUtils.hasText(question)) {
            throw new BusinessException("QUESTION_EMPTY", "问题不能为空");
        }
        final String trimmed = question.trim();
        final Long sessionId = request.sessionId();

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        executor.submit(() -> generate(trimmed, sessionId, emitter));
        return emitter;
    }

    private void generate(String question, Long sessionId, SseEmitter emitter) {
        long start = System.nanoTime();
        try {
            ensureConfigured();

            // 命中缓存时直接给出完整答案,不做「把缓存文本拆成多个片段重放」。
            //
            // 重放能让体验完全一致,但那是在假装模型又生成了一遍 ——
            // 用户以为系统真的跑了 4 秒,实际上是把一段旧文本分段发出来。
            // 这里用 cached=true 如实告诉前端,由前端决定要不要做打字机动画。
            Optional<CachedAnswer> cached = cacheService.get(question);
            if (cached.isPresent()) {
                CachedAnswer hit = cached.get();
                long costMs = elapsedMs(start);
                Long savedSessionId = historyService.record(
                        sessionId, question, hit.answer(), citedChunkIds(hit), (int) costMs);

                Map<String, Object> done = new LinkedHashMap<>();
                done.put("sessionId", savedSessionId);
                done.put("answer", hit.answer());
                done.put("citations", hit.citations());
                done.put("cached", true);
                done.put("degraded", false);
                done.put("ttftMs", costMs);
                done.put("totalMs", costMs);
                send(emitter, "done", done);
                log.info("流式请求命中缓存,耗时 {}ms", costMs);
                emitter.complete();
                return;
            }

            RetrievalResult retrieval = retrievalPipeline.retrieve(
                    question, Math.max(1, properties.getTopK()));
            List<RetrievedChunk> chunks = retrieval.chunks();
            long retrievalMs = elapsedMs(start);
            send(emitter, "meta", Map.of("retrievalMs", retrievalMs, "hitCount", chunks.size()));

            StringBuilder answer = new StringBuilder();
            CountDownLatch finished = new CountDownLatch(1);
            long[] firstTokenNanos = {0L};
            Throwable[] failure = {null};

            streamingChatModel.chat(
                    List.of(SystemMessage.from(promptBuilder.systemPrompt()),
                            UserMessage.from(promptBuilder.userPrompt(question, chunks))),
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partial) {
                            if (firstTokenNanos[0] == 0L) {
                                firstTokenNanos[0] = System.nanoTime();
                                send(emitter, "meta", Map.of("ttftMs", elapsedMs(start)));
                            }
                            answer.append(partial);
                            send(emitter, "delta", Map.of("text", partial));
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse response) {
                            finished.countDown();
                        }

                        @Override
                        public void onError(Throwable error) {
                            failure[0] = error;
                            finished.countDown();
                        }
                    });

            if (!finished.await(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException("CHAT_STREAM_TIMEOUT", "生成超时,请重试");
            }
            String text = answer.toString();
            boolean modelDegraded = false;
            if (failure[0] != null) {
                if (text.isEmpty()) {
                    // 【降级】一个字都还没发出去,可以完整地降级为「返回检索原文」——
                    // 和同步接口的行为保持一致。
                    modelDegraded = true;
                    text = fallbackAnswerBuilder.build(chunks);
                    send(emitter, "delta", Map.of("text", text));
                    log.warn("流式生成失败且尚未输出内容,降级为返回检索原文:{}",
                            failure[0].getMessage());
                } else {
                    // 已经吐了一部分内容,这时候再补一段「资料清单」只会让回答更乱。
                    // 这种情况下只能如实报错。
                    throw new BusinessException("CHAT_STREAM_FAILED",
                            "生成中断:" + failure[0].getMessage(), failure[0]);
                }
            }
            List<AnswerResponse.Citation> citations = citationBuilder.keepCitedOnly(
                    citationBuilder.buildCandidates(chunks), text);
            Long[] citedChunkIds = citations.stream()
                    .map(AnswerResponse.Citation::chunkId)
                    .toArray(Long[]::new);

            long totalMs = elapsedMs(start);
            long ttftMs = firstTokenNanos[0] == 0L
                    ? totalMs
                    : (firstTokenNanos[0] - start) / 1_000_000;

            Long savedSessionId = historyService.record(
                    sessionId, question, text, citedChunkIds, (int) totalMs);

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("sessionId", savedSessionId);
            done.put("answer", text);
            done.put("citations", citations);
            done.put("retrievalMs", retrievalMs);
            done.put("ttftMs", ttftMs);
            done.put("totalMs", totalMs);
            done.put("cached", false);
            done.put("degraded", retrieval.rerankDegraded() || modelDegraded);
            send(emitter, "done", done);

            // 同样:降级结果不写缓存(理由见 ChatService)
            if (retrieval.rerankDegraded() || modelDegraded) {
                log.warn("本次流式结果是降级产物,不写入缓存");
            } else {
                cacheService.put(question, text, citations);
            }

            log.info("流式对话完成:检索 {}ms,首字 {}ms,总耗时 {}ms,答案 {} 字",
                    retrievalMs, ttftMs, totalMs, text.length());

            emitter.complete();
        } catch (Exception ex) {
            log.error("流式对话失败", ex);
            send(emitter, "error", Map.of("message", rootMessage(ex)));
            emitter.complete();
        }
    }

    /**
     * 发送一个 SSE 事件。
     *
     * 这里吞掉异常是有意的:客户端中途断开时 send 会抛异常,
     * 但那个时刻主流程往往还在正常生成 —— 为了一个"没人接收"的事件
     * 把整次生成打断,反而会让服务端日志里出现一堆无意义的报错。
     */
    private static void send(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception ex) {
            log.debug("发送 SSE 事件 {} 失败(客户端可能已断开):{}", eventName, ex.getMessage());
        }
    }

    private void ensureConfigured() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BusinessException("CHAT_NOT_CONFIGURED",
                    "未配置对话模型密钥。请在 application-local.yml 里填写 rag.embedding.api-key,"
                            + "并以 local 配置启动应用。");
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static Long[] citedChunkIds(CachedAnswer cached) {
        return cached.citations().stream()
                .map(AnswerResponse.Citation::chunkId)
                .toArray(Long[]::new);
    }

    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

}
