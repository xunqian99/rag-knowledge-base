package cn.ragserver.health;

import cn.ragserver.chat.ChatProperties;
import cn.ragserver.embedding.EmbeddingService;
import cn.ragserver.rerank.Reranker;
import cn.ragserver.retrieval.RetrievedChunk;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 外部依赖的真实连通性探测。
 *
 * 【为什么不能只靠 /api/system/health】
 *
 * 那个接口只检查「密钥配了没有」—— 这是刻意的设计,因为健康检查会被
 * 监控系统每隔几秒调用一次,每次都真调一次外部 API 会持续消耗额度。
 *
 * 但它的代价是:**配置正确 ≠ 调用可用**。
 * 密钥填对了、模型却因为网络或服务端问题调不通,健康检查照样显示 UP。
 *
 * 所以这里提供一个**手动触发**的深度探测:真的去调一次每个外部服务,
 * 返回成功还是失败、失败原因是什么、花了多久。
 *
 * 它同时还能当降级演练的开关用 —— 第 23 项里说过,
 * **没演练过的降级等于没有降级。**
 */
@RestController
@RequestMapping("/api/system")
public class VerifyController {

    private final EmbeddingService embeddingService;
    private final Reranker reranker;
    private final ChatModel chatModel;
    private final ChatProperties chatProperties;

    public VerifyController(EmbeddingService embeddingService,
                            Reranker reranker,
                            ChatModel chatModel,
                            ChatProperties chatProperties) {
        this.embeddingService = embeddingService;
        this.reranker = reranker;
        this.chatModel = chatModel;
        this.chatProperties = chatProperties;
    }

    @PostMapping("/verify")
    public Map<String, Object> verify() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("embedding",
                probe(() -> embeddingService.embedAll(List.of("测试文本")).size() + " 维向量"));
        result.put("rerank",
                probe(() -> reranker.rerank("测试问题",
                        List.of(new RetrievedChunk(1L, 1L, "test.txt", 0, "测试文档内容", 0.0)),
                        1).size() + " 条结果"));
        result.put("chat_langchain4j_jdk",
                probe(() -> chatModel.chat("只回复两个字:你好").length() + " 字"));
        result.put("chat_spring_restclient",
                probe(this::rawChatCall));
        return result;
    }

    /**
     * 对照组:用 Spring 的 RestClient(底层是 HttpURLConnection,纯 HTTP/1.1)
     * 直接调同一个千帆对话接口。
     *
     * 如果这一项成功、上面那个 LangChain4j 的失败,
     * 就能证明问题出在 **JDK HttpClient 的 TLS/ALPN 协商**上,
     * 而不是密钥、模型或者网络 —— 因为两者只差一个 HTTP 客户端。
     */
    private String rawChatCall() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);

        RestClient client = RestClient.builder()
                .baseUrl(chatProperties.getBaseUrl())
                .requestFactory(factory)
                .build();

        Map<String, Object> body = Map.of(
                "model", chatProperties.getModel(),
                "messages", List.of(Map.of("role", "user", "content", "只回复两个字:你好")));

        String response = client.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + chatProperties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        return response == null ? "返回为空" : response.length() + " 字符";
    }

    private static String probe(Callable<String> call) {
        long start = System.nanoTime();
        try {
            return "OK: " + call.call() + " (" + (System.nanoTime() - start) / 1_000_000 + "ms)";
        } catch (Exception ex) {
            return "FAIL (" + (System.nanoTime() - start) / 1_000_000 + "ms): " + rootMessage(ex);
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}
