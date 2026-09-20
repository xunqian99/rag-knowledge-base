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
        boolean degraded) {

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
