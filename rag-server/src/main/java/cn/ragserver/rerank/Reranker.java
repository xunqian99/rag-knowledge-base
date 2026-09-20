package cn.ragserver.rerank;

import cn.ragserver.retrieval.RetrievedChunk;

import java.util.List;

/**
 * 精排的统一抽象。
 *
 * 【为什么需要精排 —— 它和召回的本质区别】
 *
 * 召回用的是 Bi-encoder(双塔):问题和文档**分别**编码成向量,再算距离。
 * 好处是文档可以预先编码建索引,能处理百万级数据;
 * 代价是问题和文档之间**没有任何交互**,信息在各自压缩成向量时就丢了。
 *
 * 精排用的是 Cross-encoder:问题和文档**拼在一起**送进同一个模型,
 * 直接输出相关性分数。每个词都能互相"看到",判断准得多;
 * 但它**无法预先计算**,每来一个问题、每一条候选都得跑一次模型。
 *
 * 所以典型架构是:
 *   Bi-encoder 召回一大批(快、能索引) -> Cross-encoder 精排一小批(慢、但准)
 *
 * 这就是为什么召回要取 50、精排只处理前 20 条。
 *
 * 【用接口抽象的好处】
 *
 * 当前实现走的是云端 API。如果以后要换成本地 ONNX 推理
 *(模型只有 100MB,CPU 也能跑,还不用花钱),只需要新增一个实现类,
 * 业务代码一行都不用改。
 */
public interface Reranker {

    /**
     * 按与查询的相关性重新排序,返回前 topN 条。
     *
     * @param query      用户的问题原文
     * @param candidates 待精排的候选,通常已经过 RRF 融合并排好序
     * @param topN       返回几条
     * @return 精排后的结果,score 字段是重排序模型给出的相关性分数(0~1)
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN);
}
