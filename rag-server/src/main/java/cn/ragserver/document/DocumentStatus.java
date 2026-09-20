package cn.ragserver.document;

/**
 * 文档处理状态。
 *
 * 上传完成 != 可以检索。文件还要经过「切分 -> 生成向量」才真正可用,
 * 这个枚举记录的就是它走到了哪一步。
 *
 *   PENDING  已上传,等待处理
 *   INDEXING 正在切分 / 生成向量
 *   INDEXED  处理完成,可以被检索到
 *   FAILED   处理失败,可配合第 23 项的重试机制
 */
public enum DocumentStatus {
    PENDING,
    INDEXING,
    INDEXED,
    FAILED
}
