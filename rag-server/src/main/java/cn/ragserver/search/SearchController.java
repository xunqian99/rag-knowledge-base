package cn.ragserver.search;

import cn.ragserver.retrieval.RetrievedChunk;
import cn.ragserver.retrieval.HybridChunkRetriever;
import cn.ragserver.retrieval.HybridRecallResult;
import cn.ragserver.retrieval.RrfFusion;
import cn.ragserver.retrieval.RetrievalPipeline;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 检索调试接口。
 *
 * 先用裸的 BM25 单路,方便用肉眼验证关键词检索的效果
 * (比如搜「自驾」「FA507UV」这类词)。
 *
 * 第 14 项会在此基础上加向量召回,扩展成混合检索。
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final EsChunkSearcher esChunkSearcher;
    private final HybridChunkRetriever hybridChunkRetriever;
    private final RrfFusion rrfFusion;
    private final RetrievalPipeline retrievalPipeline;

    public SearchController(EsChunkSearcher esChunkSearcher,
                            HybridChunkRetriever hybridChunkRetriever,
                            RrfFusion rrfFusion,
                            RetrievalPipeline retrievalPipeline) {
        this.esChunkSearcher = esChunkSearcher;
        this.hybridChunkRetriever = hybridChunkRetriever;
        this.rrfFusion = rrfFusion;
        this.retrievalPipeline = retrievalPipeline;
    }

    @GetMapping("/bm25")
    public List<RetrievedChunk> bm25(@RequestParam("q") String query,
                                     @RequestParam(defaultValue = "5") int topK) {
        return esChunkSearcher.retrieve(query, Math.min(Math.max(1, topK), 50));
    }

    /**
     * 多路召回调试接口:同时返回向量路和 BM25 路各自召回了什么。
     *
     * 用途有两个:
     *   1. 肉眼对比两路的差异,理解"为什么需要混合检索"
     *   2. 第 15 项做 RRF 融合、第 20 项调召回参数时,靠它看效果
     *
     * size 默认用配置里的 recall-size(50)。
     */
    @GetMapping("/hybrid")
    public HybridRecallResult hybrid(@RequestParam("q") String query,
                                     @RequestParam(required = false) Integer size) {
        return recall(query, size);
    }

    /**
     * RRF 融合后的排名。
     *
     * 对比着看很有价值:/hybrid 能看到两路各自的原始排名,
     * /fused 能看到融合之后谁上来了、谁掉下去了。
     * 比如某条分块在两路里都排第 2,融合后很可能会超过只在单路排第 1 的文档。
     */
    @GetMapping("/fused")
    public List<RetrievedChunk> fused(@RequestParam("q") String query,
                                      @RequestParam(required = false) Integer size) {
        return rrfFusion.fuse(recall(query, size).channels());
    }

    private HybridRecallResult recall(String query, Integer size) {
        return size == null
                ? hybridChunkRetriever.recall(query)
                : hybridChunkRetriever.recall(query, Math.min(Math.max(1, size), 100));
    }

    /**
     * 完整检索链路的结果:两路召回 -> RRF 融合 -> 精排。
     *
     * 这个接口和问答接口走的是同一段代码(RetrievalPipeline),所以它看到的就是真实结果。
     * 配合 /hybrid 和 /fused 一起用,可以逐层对比:
     *   谁被召回了 -> 融合后排名怎么变 -> 精排后谁留下来了。
     */
    @GetMapping("/reranked")
    public List<RetrievedChunk> reranked(@RequestParam("q") String query,
                                         @RequestParam(defaultValue = "5") int topN) {
        return retrievalPipeline.retrieve(query, Math.min(Math.max(1, topN), 20)).chunks();
    }
}
