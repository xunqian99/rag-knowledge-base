package cn.ragserver.retrieval;

import cn.ragserver.rerank.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 完整的检索链路:两路召回 -> RRF 融合 -> 精排。
 *
 * 单独抽出来的原因:问答接口和调试接口都要走这套流程。
 * 如果各自写一遍,调参时很容易只改了一处,两边结果对不上 ——
 * 而调试接口存在的意义恰恰是"反映问答链路真实发生了什么"。
 *
 * @param hybridChunkRetriever 两路召回
 * @param rrfFusion            RRF 融合
 * @param reranker             精排
 */
@Service
public class RetrievalPipeline {

    private static final Logger log = LoggerFactory.getLogger(RetrievalPipeline.class);

    private final HybridChunkRetriever hybridChunkRetriever;
    private final RrfFusion rrfFusion;
    private final Reranker reranker;

    public RetrievalPipeline(HybridChunkRetriever hybridChunkRetriever,
                             RrfFusion rrfFusion,
                             Reranker reranker) {
        this.hybridChunkRetriever = hybridChunkRetriever;
        this.rrfFusion = rrfFusion;
        this.reranker = reranker;
    }

    /**
     * @param query 用户的问题原文
     * @param topN  最终要几条
     */
    public RetrievalResult retrieve(String query, int topN) {
        long start = System.nanoTime();

        // 第一步:两路召回。这里要宽松,漏掉的答案后面怎么救都救不回来。
        HybridRecallResult recall = hybridChunkRetriever.recall(query);

        // 第二步:RRF 融合。只用排名不用分数,绕开两路分数不可比的问题。
        List<RetrievedChunk> fused = rrfFusion.fuse(recall.channels());

        // 第三步:精排。Cross-encoder 能看到"问题 + 文档"的完整交互,
        // 判断"这段到底能不能回答问题"—— 这是 RRF 做不到的。
        List<RetrievedChunk> reranked;
        boolean degraded = false;
        try {
            reranked = reranker.rerank(query, fused, topN);
        } catch (Exception ex) {
            // 【降级】精排不可用时,直接用 RRF 的排序结果。
            //
            // 候选池本来就是 RRF 融合出来的,直接截断成 topN 完全能用 ——
            // 只是少了 Cross-encoder 那层精判,排序质量会降一档。
            //
            // 第 16 项实测过:RRF 会把「两路都中上」的块排在「单路很强」的块前面,
            // 所以降级后确实会变差,但比直接报错好得多。
            degraded = true;
            log.warn("精排不可用,降级为 RRF 排序结果:{}", ex.getMessage());
            reranked = fused.stream().limit(topN).toList();
        }

        log.info("检索链路完成:召回 {} 条 -> 融合 {} 条 -> 取 {} 条,总耗时 {}ms {}",
                recall.channels().values().stream().mapToInt(List::size).sum(),
                fused.size(), reranked.size(),
                (System.nanoTime() - start) / 1_000_000,
                degraded ? "[精排已降级]" : "");

        // 把召回通道的失败情况一路带上 —— 响应里的降级清单要靠它
        return new RetrievalResult(reranked, degraded, recall.failedChannels());
    }
}
