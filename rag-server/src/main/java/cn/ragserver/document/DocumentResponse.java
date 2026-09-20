package cn.ragserver.document;

import java.time.Instant;

/**
 * 上传接口的返回体。
 *
 * 为什么不直接把 Document 实体返回去:
 *   1. 实体里可能有不该暴露的字段(现在没有,以后加了删除标记之类就难说)
 *   2. 实体字段随表结构变化,而接口契约应当保持稳定
 *   3. 序列化实体时,Hibernate 的懒加载代理可能触发意外查询
 */
public record DocumentResponse(
        Long id,
        String fileName,
        String fileType,
        long fileSize,
        String status,
        int chunkCount,
        String errorMessage,
        Instant createdAt) {

    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getFileName(),
                document.getFileType(),
                document.getFileSize(),
                document.getStatus().name(),
                document.getChunkCount(),
                document.getErrorMessage(),
                document.getCreatedAt());
    }
}
