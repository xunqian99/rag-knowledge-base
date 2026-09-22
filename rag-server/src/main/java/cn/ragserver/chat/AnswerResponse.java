package cn.ragserver.chat;

import java.util.List;

/**
 * 问答返回体。
 */
public record AnswerResponse(
        Long sessionId,
        String answer,
        List<Citation> citations,
        long costMs,
        boolean degraded,
        List<Degradation> degradations) {

    /**
     * 用降级清单构造响应,degraded 由清单自动推导。
     *
     * 【为什么要有这个工厂方法】
     *
     * degraded 是 degradations 的派生值(清单非空就是降级了)。
     * 让调用方各写各的,迟早会出现「清单里有降级项、布尔值却是 false」这种自相矛盾的响应。
     * 把两者的关系收在一个地方,就不会写错。
     *
     * degraded 保留是为了向后兼容:老前端只认这个布尔值,加字段不会让它坏掉。
     */
    public static AnswerResponse of(Long sessionId,
                                    String answer,
                                    List<Citation> citations,
                                    long costMs,
                                    List<Degradation> degradations) {
        List<Degradation> safe = degradations == null ? List.of() : List.copyOf(degradations);
        return new AnswerResponse(sessionId, answer, citations, costMs, !safe.isEmpty(), safe);
    }

    /**
     * 一条降级说明。
     *
     * @param code    稳定的标识,给程序做分支判断
     * @param message 直接展示给人看的文案
     */
    public record Degradation(String code, String message) {
    }

    /**
     * 一条引用来源。
     *
     * index 就是答案里出现的 [1][2] 那个编号,前端的角标靠它对上。
     */
    public record Citation(
            int index,
            long chunkId,
            long documentId,
            String fileName,
            int chunkIndex,
            double score,
            String snippet) {
    }
}
