package cn.ragserver.eval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一种检索配置、在一个评测集上的指标。
 */
public record EvaluationMetrics(
        RetrievalMode mode,
        int total,
        int hitCount,
        double hitRate,
        double hit1Rate,
        double mrr,
        Map<String, TypeMetrics> byType) {

    /**
     * 单一题型的指标。
     *
     * @param total    该题型的题目数
     * @param hitCount 命中的题目数
     * @param hitRate  命中率(Hit@K)
     * @param hit1Rate 第一位的命中率(Hit@1)
     * @param mrr      平均倒数排名
     */
    public record TypeMetrics(int total, int hitCount, double hitRate, double hit1Rate, double mrr) {
    }

    /** 空的累加器,避免调用方到处写 null 判断 */
    public static Map<String, Accumulator> newAccumulators() {
        return new LinkedHashMap<>();
    }

    /** 计算过程中的累加器 */
    public static final class Accumulator {
        private int total;
        private int hitCount;
        private int top1Count;
        private double reciprocalRankSum;

        /**
         * @param firstHitRank 第一条命中的位置(从 1 开始),没命中传 0
         */
        public void add(int firstHitRank) {
            total++;
            if (firstHitRank > 0) {
                hitCount++;
                reciprocalRankSum += 1.0 / firstHitRank;
                if (firstHitRank == 1) {
                    top1Count++;
                }
            }
        }

        public TypeMetrics toMetrics() {
            return new TypeMetrics(
                    total,
                    hitCount,
                    total == 0 ? 0 : (double) hitCount / total,
                    total == 0 ? 0 : (double) top1Count / total,
                    total == 0 ? 0 : reciprocalRankSum / total);
        }
    }
}
