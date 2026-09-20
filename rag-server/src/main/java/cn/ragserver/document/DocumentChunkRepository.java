package cn.ragserver.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    /** 按文档取出所有分块,按序号排列。第 7 项生成向量、第 11 项删除文档时会用到。 */
    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(Long documentId);

    long countByDocumentId(Long documentId);

    /**
     * 删除某个文档的全部分块。重新索引时先清空旧块,再写新块。
     *
     * 【为什么用批量删除,@Modifying + @Query,而不是派生方法 deleteByDocumentId】
     *
     * 这不是风格问题,是踩过坑之后的修正。
     *
     * 派生方法 `deleteByDocumentId` 的做法是「先把实体查出来,再排队删除」,
     * 删除动作会进 Hibernate 的动作队列。而 Hibernate 执行队列的顺序是
     * **INSERT 先于 DELETE** —— 于是重新索引时:
     *
     *   1. 新分块先 INSERT 进来
     *   2. 旧分块才被 DELETE
     *   3. 新旧数据撞在唯一约束 (document_id, chunk_index) 上,直接报错
     *
     * 批量删除则是一条直接的 DELETE 语句,立即执行,不参与动作队列的排序,
     * 从根上避开这个问题。顺带还少查一次数据库。
     */
    @Modifying
    @Query("delete from DocumentChunk c where c.documentId = :documentId")
    int deleteAllByDocumentId(@Param("documentId") Long documentId);
}
