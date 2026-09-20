package cn.ragserver.retrieval;

import cn.ragserver.common.VectorLiteral;
import cn.ragserver.embedding.EmbeddingService;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 向量召回:用 pgvector 做语义相似度检索。
 */
@Repository
@Order(1)
public class VectorChunkRetriever implements ChunkRetriever {

    /**
     * `<=>` 是 pgvector 的余弦距离运算符:值越小越相似。
     *
     * 用 CTE(WITH q AS ...)把查询向量只传一次,而不是在
     * SELECT 和 ORDER BY 里各写一个占位符 —— 少一次参数传递,也不容易写错。
     *
     * 两个过滤条件的用意:
     *   c.embedding IS NOT NULL  还没生成向量的分块不能参与比较
     *   d.status = 'INDEXED'     处理失败的文档不应被检索到
     */
    private static final String SEARCH_SQL = """
            WITH q AS (SELECT CAST(? AS vector) AS v)
            SELECT c.id, c.document_id, c.chunk_index, c.content, d.file_name,
                   1 - (c.embedding <=> q.v) AS score
            FROM document_chunk c
            JOIN document d ON d.id = c.document_id
            CROSS JOIN q
            WHERE c.embedding IS NOT NULL
              AND d.status = 'INDEXED'
            ORDER BY c.embedding <=> q.v
            LIMIT ?
            """;

    private static final RowMapper<RetrievedChunk> ROW_MAPPER = (rs, rowNum) -> new RetrievedChunk(
            rs.getLong("id"),
            rs.getLong("document_id"),
            rs.getString("file_name"),
            rs.getInt("chunk_index"),
            rs.getString("content"),
            rs.getDouble("score"));

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;

    public VectorChunkRetriever(JdbcTemplate jdbcTemplate, EmbeddingService embeddingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
    }

    @Override
    public String name() {
        return "vector";
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, int topK) {
        // 注意:问题用的是和文档完全相同的那个 embedding 模型 ——
        // 不同模型产出的向量不在同一个语义空间里,算相似度毫无意义。
        float[] queryVector = embeddingService.embedAll(List.of(query)).get(0);
        return jdbcTemplate.query(SEARCH_SQL, ROW_MAPPER, VectorLiteral.of(queryVector), topK);
    }
}
