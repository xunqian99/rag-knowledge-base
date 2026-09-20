package cn.ragserver.document;

import cn.ragserver.common.VectorLiteral;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * 分块向量的写入。
 *
 * 【为什么单独用 JDBC,不走 JPA】
 *
 * Hibernate 不认识 PostgreSQL 的 vector 类型,也没法把 Java 的 float[]
 * 映射到它。硬要支持就得写自定义 UserType,代码量很大,收益却很小。
 *
 * 而这里的需求非常简单 —— 一条 UPDATE 语句而已。
 * 用 JdbcTemplate 直接写原生 SQL 是最省事的做法。
 * 这种「主体用 ORM、个别特殊类型走原生 SQL」的搭配在真实项目里很常见。
 */
@Repository
public class DocumentChunkVectorDao {

    /**
     * 用 CAST(? AS vector) 而不是 ?::vector。
     *
     * PostgreSQL 的 :: 是类型转换语法,但它和 JDBC 的占位符 ? 写在一起时
     * 容易产生解析歧义。用标准的 CAST 语法更稳妥,可读性也更好。
     */
    private static final String UPDATE_SQL =
            "UPDATE document_chunk SET embedding = CAST(? AS vector) WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;

    public DocumentChunkVectorDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 批量回填向量。
     *
     * 用 batchUpdate 而不是循环调 update:多个 UPDATE 会合并成批处理,
     * 减少与数据库的网络往返。这里能真正生效 ——
     * 因为不涉及自增主键的生成,不像 INSERT 那样被 Hibernate 的 IDENTITY 策略挡住。
     */
    public void updateEmbeddings(List<ChunkVector> items) {
        if (items.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(UPDATE_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ChunkVector item = items.get(i);
                ps.setString(1, VectorLiteral.of(item.vector()));
                ps.setLong(2, item.chunkId());
            }

            @Override
            public int getBatchSize() {
                return items.size();
            }
        });
    }

    /** 一个分块 ID 与它对应向量的组合 */
    public record ChunkVector(long chunkId, float[] vector) {
    }
}
