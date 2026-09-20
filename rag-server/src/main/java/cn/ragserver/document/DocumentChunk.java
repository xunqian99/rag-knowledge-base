package cn.ragserver.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 文本分块实体,对应 document_chunk 表。
 *
 * 【重要】表里还有两个列没有映射到这里:embedding 和 metadata。
 *
 * 不映射 embedding 的原因:Hibernate 不认识 PostgreSQL 的 vector 类型,
 * 硬映射会在启动时报错。而且第 6 项根本不写向量,让它保持 NULL 即可。
 * 第 7 项写入向量时会改用原生 SQL(JDBC 参数直接传字符串形式的向量字面量)。
 *
 * 这个取舍的通用说法是:JPA 实体只需要映射「业务代码要用到的列」,
 * 不是数据库表的逐字段镜像。
 *
 * 另外,这里用普通的 documentId 外键字段,而没有用 @ManyToOne 关联对象。
 * 理由见决策记录 —— 两者都能用,取舍在「方便」和「可预测性」之间。
 */
@Entity
@Table(name = "document_chunk")
@Getter
@Setter
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属文档的 ID,对应 document.id */
    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /** 这块在原文中的序号,从 0 开始 */
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    /** 分块后的文本内容 */
    @Column(name = "content", nullable = false)
    private String content;

    /**
     * 附加信息,数据库里是 jsonb 类型。
     *
     * 第 10 项开始写入,存的是所属章节路径,例如
     * {"section":"员工手册 > 第一章 考勤管理 > 1.1 工作时间"}
     *
     * 章节路径其实已经拼在 content 的开头了,这里再结构化存一份的用处是:
     * 前端可以单独展示来源章节,以后也能按章节过滤检索。
     *
     * 用 @JdbcTypeCode(SqlTypes.JSON) 让 Hibernate 6 直接把 String 当 jsonb 写入。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
