package cn.ragserver.retrieval;

import java.util.List;

/**
 * 一次完整检索的结果,附带"是否降级"的标记。
 *
 * 【为什么要把这个标记一路传出去】
 *
 * 精排不可用时会降级用 RRF 的排序结果 —— 答案通常还能用,但排序质量确实降了一档。
 *
 * 如果这个信息只写进服务端日志,前端和用户就完全不知道:
 * 他们以为自己看到的是完整链路的结果,实际上少了一个环节。
 *
 * **降级可以不完美,但不能瞒着。** 标记一路传到响应里,
 * 前端可以据此提示"当前结果未经精排",用户也就有了判断依据。
 */
public record RetrievalResult(List<RetrievedChunk> chunks, boolean rerankDegraded) {

    public static RetrievalResult normal(List<RetrievedChunk> chunks) {
        return new RetrievalResult(chunks, false);
    }
}
