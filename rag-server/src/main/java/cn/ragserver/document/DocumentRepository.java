package cn.ragserver.document;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 继承 JpaRepository 就白拿一整套 CRUD 方法(save / findById / delete / findAll...),
 * 不用写一行实现代码 —— Spring Data 会在启动时按方法名生成实现。
 */
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * 按存储路径判断记录是否存在。
     *
     * 方法名本身就是要执行的查询:exists + By + 字段名。
     * 它在本项目里承担一个重要职责 ——
     * 入库失败后清理文件之前,先用它确认数据库里真的没有这条记录(见 DocumentService)。
     */
    boolean existsByStoragePath(String storagePath);
}
