package cn.ragserver.eval;

/**
 * 评测要对比的检索配置。
 *
 * 这四种正好是项目一路演进过来的四个阶段,
 * 把它们放在一起对比,就能回答"每一步改造到底带来了多少收益"。
 */
public enum RetrievalMode {

    VECTOR("向量"),
    BM25("BM25"),
    HYBRID("混合"),
    HYBRID_RERANK("混合+精排");

    private final String label;

    RetrievalMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
