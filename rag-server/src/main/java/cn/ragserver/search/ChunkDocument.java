package cn.ragserver.search;

/**
 * 写进 Elasticsearch 的分块文档。
 *
 * 字段选择和关系库那边基本对齐,但有两个区别:
 *
 *   file_name / section 用 keyword 类型 —— 不分词,只做精确匹配和聚合。
 *     如果也用 text,「员工手册」会被切成「员工」「工手」「手册」,
 *     按文件名过滤就完全不可靠了。
 *
 *   content 用 text + cjk 分析器 —— 这正是要全文检索的字段。
 */
public record ChunkDocument(
        long chunkId,
        long documentId,
        String fileName,
        int chunkIndex,
        String section,
        String content) {
}
