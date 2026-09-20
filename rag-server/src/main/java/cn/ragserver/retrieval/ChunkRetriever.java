package cn.ragserver.retrieval;

import java.util.List;

/**
 * 一路召回的统一抽象。
 *
 * 为什么需要它:第 15 项(RRF 融合)和第 16 项(Rerank 精排)要站在两路之上工作,
 * 它们只应该知道「我拿到了一路召回结果」,不该关心底下是 pgvector 还是 Elasticsearch。
 *
 * 接口是为了让上层不受下层变化影响 —— 以后把 BM25 换成别的检索、
 * 或者再加一路召回,上层代码一行都不用改。
 *
 * 实现类都会被自动收集进 HybridChunkRetriever,所以新增一路召回
 * 只需要写一个新实现类,不需要改动任何已有代码。
 */
public interface ChunkRetriever {

    /**
     * 这一路的名称,用于日志和调试接口,例如 vector / bm25
     */
    String name();

    /**
     * 按查询文本召回分块。
     *
     * 注意参数是原始的查询文本而不是向量 ——
     * 需要向量的实现(向量召回)自己负责把文本转向量。
     * 这样上层调用时不用区分「这一路要不要先向量化」。
     *
     * @param query 用户的问题原文
     * @param topK  这一路最多返回几条
     */
    List<RetrievedChunk> retrieve(String query, int topK);
}
