package cn.ragserver.retrieval;

import java.util.List;
import java.util.Map;

/**
 * 多路召回的原始结果。
 *
 * 刻意保留成「每路一个列表」而不是合并成一个列表:
 * 两路的分数不可比(余弦相似度是 0~1,BM25 得分无上界),
 * 合并必须基于排名而不是分数,那是第 15 项 RRF 要做的事。
 *
 * 这个对象的主要用途是调试 —— 眼睛能同时看到两路各自召回了什么,
 * 才谈得上调参数。
 *
 * @param query    原始查询
 * @param size     每路取了多少条
 * @param channels 路名 -> 该路的召回结果,顺序与召回器注册顺序一致
 */
public record HybridRecallResult(
        String query,
        int size,
        Map<String, List<RetrievedChunk>> channels,
        List<String> failedChannels) {

    /**
     * 不带通道健康信息的三参数构造。
     *
     * 调试接口不关心"哪路挂了"(它本来就只调一路),用这个更省事。
     */
    public HybridRecallResult(String query, int size, Map<String, List<RetrievedChunk>> channels) {
        this(query, size, channels, List.of());
    }

    /**
     * 是否有召回通道失败。
     *
     * 【这个信息以前被丢掉了】
     *
     * 单路召回失败只打了条日志,没有往上传 —— 结果是 ES 挂掉、只走向量召回的请求,
     * 响应里的 degraded 仍然是 false。**降级可以不完美,但不能瞒着。**
     */
    public boolean hasFailedChannel() {
        return !failedChannels.isEmpty();
    }
}
