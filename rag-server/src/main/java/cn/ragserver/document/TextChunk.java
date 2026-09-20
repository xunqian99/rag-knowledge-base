package cn.ragserver.document;

/**
 * 切分出来的一个分块。
 *
 * @param content     最终要入库的文本,已经带上了章节面包屑前缀
 * @param sectionPath 所属章节路径,例如「员工手册 > 第一章 考勤管理 > 1.1 工作时间」
 */
public record TextChunk(String content, String sectionPath) {
}
