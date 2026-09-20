package cn.ragserver.embedding;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 向量模型的装配。
 */
@Configuration
public class EmbeddingConfig {

    /**
     * 没配置密钥时用的占位值。
     *
     * 【设计取舍】为什么不在这里直接抛异常拒绝启动?
     *
     * 一个服务应该做到:某个外部依赖没配好时,服务本身照常启动,
     * 只有依赖它的那个功能不可用 —— 而不是整个应用起不来。
     * 否则运维半夜就会收到「服务挂了」的告警,而实际上只是少配了一个密钥。
     *
     * 真正的校验放在 EmbeddingService 里,时间点更靠后、报错信息也更具体。
     * 同时健康检查会把这个依赖标记为 DOWN,让人一眼看出问题在哪。
     */
    private static final String NOT_CONFIGURED = "NOT_CONFIGURED";

    @Bean
    public EmbeddingModel embeddingModel(EmbeddingProperties properties) {
        String apiKey = StringUtils.hasText(properties.getApiKey())
                ? properties.getApiKey()
                : NOT_CONFIGURED;

        return OpenAiEmbeddingModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(apiKey)
                .modelName(properties.getModel())
                .timeout(properties.getTimeout())
                .maxRetries(properties.getMaxRetries())
                .build();
    }
}
