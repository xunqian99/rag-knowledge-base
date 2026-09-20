package cn.ragserver.retrieval;

/**
 * 一次检索命中的分块。
 *
 * @param score 这一路的打分。
 *
 *              这个字段的名字特意取得中性,因为它的含义**取决于产生它的那一步**:
 *                向量召回 -> 余弦相似度,0~1 之间
 *                BM25 召回 -> BM25 得分,没有上界(实测有 1.6、3.8、6.4)
 *                RRF 融合  -> RRF 得分,0.0X 量级
 *
 *              这正是不做「向量分 × 0.7 + BM25 分 × 0.3」的原因 ——
 *              三种分数根本不在同一个尺度上,相加没有意义。
 */
public record RetrievedChunk(
        long chunkId,
        long documentId,
        String fileName,
        int chunkIndex,
        String content,
        double score) {
}
