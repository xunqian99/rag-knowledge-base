package cn.ragserver.retrieval;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF(Reciprocal Rank Fusion,倒数排名融合)。
 *
 * 公式只有一行:
 *
 *   RRF得分(文档) = Σ  1 / (k + 该文档在这一路里的排名)
 *                 各路
 *
 * 排名从 1 开始,k 是平滑常数(通常取 60)。
 *
 * 【它为什么有效】
 *
 * 假设 k=60,有两份文档:
 *   文档 A:向量路排第 1,BM25 路没召回  →  1/(60+1)              = 0.0164
 *   文档 B:向量路排第 2,BM25 路排第 1   →  1/(60+2) + 1/(60+1)   = 0.0325
 *
 * B 赢了 A。尽管 A 在某一路排第一,但 B 被两路同时认可。
 * **多路都认为相关的文档,排名会显著上升** —— 这就是 RRF 的核心价值。
 *
 * 【为什么只用排名、不用分数】
 *
 * 因为三种分数根本不可比:
 *   向量召回 -> 余弦相似度,0~1
 *   BM25   -> 得分无上界,实测 1.6 / 3.8 / 6.4
 *
 * 加权求和的前提是先归一化,但归一化方式没有标准答案,
 * 而且用全局最大最小值归一化时,新来一条异常数据会把所有分数压扁。
 *
 * **RRF 完全绕开了归一化问题**,这也是它在多路检索场景被广泛采用的原因。
 *
 * 【它的缺点(要知道)】
 *
 * 它把分数信息完全丢掉了。如果某一路非常确信(分数远超第二名),
 * RRF 看不出来,只当成"排第一"。
 * 好在 RRF 对 k 不敏感,实践中很鲁棒,这个代价通常可以接受。
 */
@Component
public class RrfFusion {

    private final RetrievalProperties properties;

    public RrfFusion(RetrievalProperties properties) {
        this.properties = properties;
    }

    /**
     * 把多路召回结果融合成一个排名。
     *
     * @param channels 路名 -> 该路的有序召回结果
     * @return 融合后的分块列表,按 RRF 得分从高到低;每条记录的 score 是 RRF 得分
     */
    public List<RetrievedChunk> fuse(Map<String, List<RetrievedChunk>> channels) {
        int k = properties.getRrfK();

        // 用 LinkedHashMap 保持"首次出现"的顺序,后续排序时才不会因为
        // 相等得分而出现随机的相对顺序。
        Map<Long, Double> scores = new LinkedHashMap<>();
        Map<Long, RetrievedChunk> chunksById = new LinkedHashMap<>();

        for (Map.Entry<String, List<RetrievedChunk>> channel : channels.entrySet()) {
            List<RetrievedChunk> hits = channel.getValue();
            for (int i = 0; i < hits.size(); i++) {
                RetrievedChunk hit = hits.get(i);
                int rank = i + 1;
                // merge:同一个分块被多路召回时得分累加,这正是 RRF 的精髓。
                scores.merge(hit.chunkId(), 1.0 / (k + rank), Double::sum);
                // 只保留第一次见到的那份内容,避免同一条出现两遍。
                chunksById.putIfAbsent(hit.chunkId(), hit);
            }
        }

        return scores.entrySet().stream()
                .sorted(Comparator
                        .comparingDouble((Map.Entry<Long, Double> entry) -> entry.getValue())
                        .reversed()
                        // 得分相同时按 chunkId 排序,保证同样的输入永远得到同样的输出顺序。
                        // 不确定的结果会让"改了什么导致效果变化"变得无法分析。
                        .thenComparingLong(entry -> entry.getKey()))
                .map(entry -> withScore(chunksById.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    private static RetrievedChunk withScore(RetrievedChunk origin, double score) {
        return new RetrievedChunk(
                origin.chunkId(),
                origin.documentId(),
                origin.fileName(),
                origin.chunkIndex(),
                origin.content(),
                score);
    }
}
