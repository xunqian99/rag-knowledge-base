package cn.ragserver.retrieval;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 召回阶段配置,对应 application.yml 里的 rag.retrieval */
@ConfigurationProperties(prefix = "rag.retrieval")
@Getter
@Setter
public class RetrievalProperties {

    /**
     * 每一路召回的条数。
     *
     * 为什么是 50 而不是最终要用的 5:
     * 召回和精排的目标不同 ——
     *   召回要「宁可多召回,不可漏」,漏掉的答案后面怎么救都救不回来
     *   精排再从这 50 条里挑出真正最相关的几条
     *
     * 太小可能漏(正确答案排在第 30 位就丢了),太大拖慢精排也引入噪声。
     * 50 是经验起点,第 20 项会做参数对比来决定。
     */
    private int recallSize = 50;

    /**
     * RRF 的平滑常数 k。
     *
     * 作用:削弱排名靠前的绝对优势。
     *   k 大 -> 融合结果更平均,不容易被某一路的第一名带偏
     *   k 小 -> 更偏向各路的第一名
     *
     * 60 是 RRF 原论文推荐的经验值。好消息是 RRF 对 k 并不敏感,
     * 30~100 之间结果差别不大,所以不需要为它专门调参。
     */
    private int rrfK = 60;
}
