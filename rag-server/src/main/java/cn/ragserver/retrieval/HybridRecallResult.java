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
        Map<String, List<RetrievedChunk>> channels) {
}
