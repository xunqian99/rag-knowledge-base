package cn.ragserver.chat;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 对话模型的装配。
 *
 * 和向量模型一样,千帆 v2 提供的是 OpenAI 兼容接口,所以直接复用
 * LangChain4j 的 OpenAI 客户端,换个 baseUrl 和模型名即可。
 */
@Configuration
public class ChatConfig {

    /** 未配置密钥时的占位值,理由同 EmbeddingConfig:让服务能起来 */
    private static final String NOT_CONFIGURED = "NOT_CONFIGURED";

    @Bean
    public ChatModel chatModel(ChatProperties properties) {
        String apiKey = StringUtils.hasText(properties.getApiKey())
                ? properties.getApiKey()
                : NOT_CONFIGURED;

        return OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(apiKey)
                .modelName(properties.getModel())
                .temperature(properties.getTemperature())
                .timeout(properties.getTimeout())
                .maxRetries(properties.getMaxRetries())
                .build();
    }

    /**
     * 流式对话模型。
     *
     * 和上面的 ChatModel 是同一个模型、同一份配置,区别只在调用方式:
     * 非流式要等模型把整段答案生成完才返回,流式则是一边生成一边往回推片段。
     *
     * 对用户来说这是「等 4 秒后刷出一整段」和「0.5 秒后开始逐字出现」的差别 ——
     * **总耗时其实差不多,但感知快得多**。
     *
     * 两个 Bean 都注册,是因为两种方式各有适用场景:
     * 流式给前端交互用(第 21 项),非流式给评测和批处理用(第 18 项)。
     * 评测要的是完整的答案文本,用流式只会徒增复杂度。
     */
    @Bean
    public StreamingChatModel streamingChatModel(ChatProperties properties) {
        String apiKey = StringUtils.hasText(properties.getApiKey())
                ? properties.getApiKey()
                : NOT_CONFIGURED;

        return OpenAiStreamingChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(apiKey)
                .modelName(properties.getModel())
                .temperature(properties.getTemperature())
                .timeout(properties.getTimeout())
                .build();
    }
}
