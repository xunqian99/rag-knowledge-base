package cn.ragserver.chat;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 对话模型配置,对应 application.yml 里的 rag.chat。
 */
@ConfigurationProperties(prefix = "rag.chat")
@Getter
@Setter
public class ChatProperties {

    private String baseUrl = "https://qianfan.baidubce.com/v2";

    private String apiKey = "";

    private String model = "ernie-4.5-turbo-128k";

    /**
     * 采样温度,范围一般是 0~1。
     * 事实型问答要低(0~0.3),创意写作才需要高。
     */
    private double temperature = 0.2;

    /** 检索返回的分块数量 */
    private int topK = 3;

    private Duration timeout = Duration.ofSeconds(60);

    private int maxRetries = 2;
}
