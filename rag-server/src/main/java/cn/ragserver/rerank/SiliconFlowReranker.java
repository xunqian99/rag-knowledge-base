package cn.ragserver.rerank;

import cn.ragserver.common.BusinessException;
import cn.ragserver.retrieval.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/**
 * 基于硅基流动 API 的重排序实现。
 */
@Service
public class SiliconFlowReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(SiliconFlowReranker.class);

    private final RerankProperties properties;
    private final RestClient restClient;

    public SiliconFlowReranker(RerankProperties properties) {
        this.properties = properties;

        // 显式设置超时。默认的 RestClient 没有读超时 ——
        // 外部接口一旦挂起,请求线程会被无限期占住,这是很危险的状态。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getTimeout().toMillis());
        factory.setReadTimeout((int) properties.getTimeout().toMillis());

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        ensureConfigured();

        // 精排是最贵最慢的一步,必须先截断候选数
        List<RetrievedChunk> limited = candidates.stream()
                .limit(Math.max(1, properties.getCandidateLimit()))
                .toList();

        long start = System.nanoTime();
        RerankResponse response = callWithRetry(query, limited, topN);

        if (response == null || response.results() == null || response.results().isEmpty()) {
            throw new BusinessException("RERANK_EMPTY", "重排序接口没有返回结果");
        }

        log.info("重排序完成:候选 {} 条 -> 返回 {} 条,耗时 {}ms,input_tokens={}",
                limited.size(),
                response.results().size(),
                (System.nanoTime() - start) / 1_000_000,
                response.meta() == null || response.meta().tokens() == null
                        ? "-" : response.meta().tokens().inputTokens());

        return response.results().stream()
                // 越界保护:第三方返回的下标理论上一定合法,
                // 但拿它直接去 get() 一旦越界就是运行时异常,加一层便宜的校验更稳。
                .filter(result -> result.index() >= 0 && result.index() < limited.size())
                .limit(Math.max(1, topN))
                .map(result -> withScore(limited.get(result.index()), result.relevanceScore()))
                .toList();
    }

    /**
     * 带重试的调用。
     *
     * 不是所有失败都该重试 —— 参数写错、密钥无效这类问题重试一万次结果还是一样,
     * 只是白白拖慢响应。只有服务端错误(5xx)、限流(429)和网络抖动才值得再试一次。
     */
    private RerankResponse callWithRetry(String query, List<RetrievedChunk> documents, int topN) {
        int maxAttempts = Math.max(1, properties.getMaxRetries() + 1);
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return restClient.post()
                        .uri("/rerank")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new RerankRequest(
                                properties.getModel(),
                                query,
                                documents.stream().map(RetrievedChunk::content).toList(),
                                Math.max(1, topN),
                                Boolean.FALSE))
                        .retrieve()
                        .body(RerankResponse.class);
            } catch (Exception ex) {
                lastError = ex;
                if (!isRetryable(ex) || attempt >= maxAttempts) {
                    break;
                }
                log.warn("重排序第 {} 次调用失败,{}ms 后重试:{}",
                        attempt, 200L * attempt, ex.getMessage());
                sleepQuietly(200L * attempt);
            }
        }
        throw new BusinessException("RERANK_FAILED",
                "重排序调用失败:" + (lastError == null ? "未知原因" : lastError.getMessage()), lastError);
    }

    private static boolean isRetryable(Exception ex) {
        if (ex instanceof RestClientResponseException httpError) {
            // 5xx 是服务端的问题,429 是限流 —— 这两种重试有意义。
            // 4xx 里剩下的基本是请求本身写错了,重试不会有不同结果。
            return httpError.getStatusCode().is5xxServerError()
                    || httpError.getStatusCode().value() == 429;
        }
        // 连接超时、读超时这类 IO 异常属于网络抖动,重试通常能成功
        return true;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void ensureConfigured() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BusinessException("RERANK_NOT_CONFIGURED",
                    "未配置重排序密钥。请在 application-local.yml 里填写 rag.rerank.api-key,"
                            + "并以 local 配置启动应用。");
        }
    }

    private static RetrievedChunk withScore(RetrievedChunk origin, Double score) {
        return new RetrievedChunk(
                origin.chunkId(),
                origin.documentId(),
                origin.fileName(),
                origin.chunkIndex(),
                origin.content(),
                score == null ? 0.0 : score);
    }
}
