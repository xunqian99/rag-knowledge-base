package cn.ragserver.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 一条消息,对应 chat_message 表。
 *
 * 一次问答会写两条记录:用户提问(role=USER)、模型回答(role=ASSISTANT)。
 */
@Entity
@Table(name = "chat_message")
@Getter
@Setter
public class ChatMessage {

    /** 消息角色的取值 */
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "content", nullable = false)
    private String content;

    /**
     * 引用溯源:这条回答引用了哪几个分块。
     *
     * 数据库里是 PostgreSQL 的 bigint[] 数组类型。
     * Hibernate 6 能直接把 Long[] 映射到 SQL 数组,不需要额外配置。
     */
    @Column(name = "cited_chunk_ids")
    private Long[] citedChunkIds;

    /** 这一轮耗时,毫秒。第 21 项统计首字延迟、第 23 项做性能对比时要用 */
    @Column(name = "latency_ms")
    private Integer latencyMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
