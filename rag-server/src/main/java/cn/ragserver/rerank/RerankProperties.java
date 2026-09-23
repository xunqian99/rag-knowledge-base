package cn.ragserver.rerank;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 重排序配置,对应 application.yml 里的 rag.rerank */
@ConfigurationProperties(prefix = "rag.rerank")
@Getter
@Setter
public class RerankProperties {

    private String baseUrl = "https://api.siliconflow.cn/v1";

    private String apiKey = "";

    /**
     * 重排序模型。
     *
     * 选 BAAI/bge-reranker-v2-m3 的理由:
     *   - 它是文本重排的事实标准,社区资料最多,面试官大概率认识
     *   - 568M 参数,精排要逐条跑,模型大小直接决定延迟和花费
     *   - 中文是它的主场
     *   - 8K 上下文,而我们的分块最长才 500 字
     *
     * 8B 级别的模型(比如 Qwen3-Reranker-8B)效果可能更好,但慢十几倍、贵十几倍,
     * 对精排这种高频小批量调用来说不划算。
     */
    private String model = "BAAI/bge-reranker-v2-m3";

    /**
     * 送去精排的最大候选数。
     *
     * 精排是整个链路里最贵、最慢的一步 —— 召回可以预先建索引,
     * 精排只能对每一条候选逐条跑模型。所以候选数必须设上限。
     *
     * 20 是个折中:RRF 融合后的前 20 条基本已经覆盖了正确答案,
     * 再往后加收益很小,却会让延迟和花费线性增长。
     */
    private int candidateLimit = 20;

    /** 单次调用超时。正常精排只要 300~500ms,15s 已经很宽松。 */
    private Duration timeout = Duration.ofSeconds(15);

    /**
     * 失败重试次数。
     *
     * 只对「值得重试」的失败生效:
     *   5xx 和 429(服务端问题、限流)—— 重试有意义
     *   4xx 参数错误            —— 重试一万次也一样,直接放弃
     *   连接/读超时             —— 属于网络抖动,重试通常能好
     */
    private int maxRetries = 2;
}
