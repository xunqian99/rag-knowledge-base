package cn.ragserver.retrieval;

import cn.ragserver.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多路召回的编排。
 *
 * 【为什么不直接依赖具体的两个实现】
 *
 * 这里注入的是 List&lt;ChunkRetriever&gt; —— Spring 会把所有实现类自动收集进来。
 * 好处有两个:
 *
 *   1. 新增一路召回只需要写一个新实现类,这个类一行都不用改
 *   2. 避免了包之间的循环依赖:retrieval 包不需要反过来依赖 search 包
 *
 * 【为什么先串行,不并行】
 *
 * 两路召回互相独立,理论上可以并行。但算一下收益:
 *   向量那路:问题转向量要调 embedding API(约 500ms)+ pgvector 查询(几毫秒)
 *   BM25 那路:ES 查询(约 20ms)
 *
 * 并行最多省下 20ms,而总耗时是 500ms 量级 —— 为了 4% 的收益
 * 引入线程池和并发复杂度,不划算。
 *
 * 这个判断先记下来,第 23 项做性能优化时以实测数据为准再决定。
 * **不为了「看起来优化了」而增加复杂度。**
 */
@Service
public class HybridChunkRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridChunkRetriever.class);

    private final List<ChunkRetriever> retrievers;
    private final RetrievalProperties properties;

    public HybridChunkRetriever(List<ChunkRetriever> retrievers, RetrievalProperties properties) {
        this.retrievers = retrievers;
        this.properties = properties;
        log.info("召回器已注册:{}", retrievers.stream().map(ChunkRetriever::name).toList());
    }

    public HybridRecallResult recall(String query) {
        return recall(query, properties.getRecallSize());
    }

    public HybridRecallResult recall(String query, int size) {
        Map<String, List<RetrievedChunk>> channels = new LinkedHashMap<>();
        // 记录哪几路挂了。以前只打日志不上报,导致"ES 挂了"这种情况
        // 在响应里看起来和一切正常没有区别。
        List<String> failedChannels = new ArrayList<>();
        long start = System.nanoTime();
        int failed = 0;

        for (ChunkRetriever retriever : retrievers) {
            long channelStart = System.nanoTime();
            try {
                List<RetrievedChunk> hits = retriever.retrieve(query, size);
                channels.put(retriever.name(), hits);
                log.info("召回[{}] 命中 {} 条,耗时 {}ms",
                        retriever.name(), hits.size(), (System.nanoTime() - channelStart) / 1_000_000);
            } catch (Exception ex) {
                // 【降级】单路召回失败不影响整体 —— 用另一路的结果继续往下走。
                // 比如 Elasticsearch 挂了就只用向量召回,反过来也一样。
                // 答案质量会下降,但「稍差一点的答案」远好过「直接报错」。
                failed++;
                failedChannels.add(retriever.name());
                channels.put(retriever.name(), List.of());
                log.warn("召回[{}] 不可用,该路跳过,继续用其余通道:{}",
                        retriever.name(), ex.getMessage());
            }
        }

        // 只有全部通道都失败,才认为整体不可用
        if (!retrievers.isEmpty() && failed == retrievers.size()) {
            throw new BusinessException("RECALL_ALL_FAILED", "所有召回通道均不可用");
        }

        log.info("多路召回完成({} 路失败),总耗时 {}ms",
                failed, (System.nanoTime() - start) / 1_000_000);
        return new HybridRecallResult(query, size, channels, List.copyOf(failedChannels));
    }
}
