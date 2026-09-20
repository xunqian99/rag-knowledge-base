package cn.ragserver.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 文档实体,对应数据库里的 document 表。
 *
 * @Table 显式写了表名:默认命名策略也能推出 document,
 * 但显式写出来更清楚,也不会因为以后改类名而悄悄换表。
 */
@Entity
@Table(name = "document")
@Getter
@Setter
public class Document {

    /** 对应 BIGSERIAL 主键,IDENTITY 表示让数据库自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户上传时的原始文件名,用于展示、下载时回填 */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** 文件后缀:txt / pdf / docx ... */
    @Column(name = "file_type", nullable = false, length = 20)
    private String fileType;

    /** 字节数 */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /**
     * 文件的相对存储路径,例如 2026/09/19/3f2a....txt
     *
     * 存相对路径而不是绝对路径:绝对路径绑定了这台机器,
     * 换台机器或放进容器里就失效了。
     */
    @Column(name = "storage_path", nullable = false, length = 500)
    private String storagePath;

    /**
     * 用 @Enumerated(STRING) 而不是默认的 ORDINAL。
     *
     * ORDINAL 存的是枚举序号 0/1/2/3,以后在枚举中间插一个新值,
     * 已有数据的含义就整体错位了 —— 这是 JPA 里非常经典的一个坑。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DocumentStatus status;

    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount;

    /**
     * 处理失败时的原因,成功时为 null。
     * 由 Flyway 的 V2 迁移脚本加入(第 6 项)。
     */
    @Column(name = "error_message")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
