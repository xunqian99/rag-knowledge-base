package cn.ragserver.embedding;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 向量模型配置,对应 application.yml 里的 rag.embedding。
 */
@ConfigurationProperties(prefix = "rag.embedding")
@Getter
@Setter
public class EmbeddingProperties {

    /** 千帆 v2 的 OpenAI 兼容接口地址 */
    private String baseUrl = "https://qianfan.baidubce.com/v2";

    /** API Key。留空表示未配置,此时应用能启动,但上传文档会明确报错。 */
    private String apiKey = "";

    /** 模型名。bge-large-zh 是中文检索效果较好的一个。 */
    private String model = "bge-large-zh";

    /** 向量维度。必须与模型实际输出一致,也对应建表时的 vector(N)。 */
    private int dimension = 1024;

    /** 一次请求携带多少个文本。批量调用能显著减少网络往返。 */
    private int batchSize = 16;

    /** 单次请求超时。向量化是外部调用,必须设超时,不能无限等。 */
    private Duration timeout = Duration.ofSeconds(30);

    /** 失败重试次数。网络抖动导致的偶发失败,重试一次通常就好了。 */
    private int maxRetries = 2;
}
