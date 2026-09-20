package cn.ragserver.document;

import cn.ragserver.common.BusinessException;
import cn.ragserver.cache.KnowledgeBaseVersion;
import cn.ragserver.embedding.EmbeddingService;
import cn.ragserver.search.ChunkDocument;
import cn.ragserver.search.ChunkIndexService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档索引服务:把落盘的原始文件变成一条条可检索的分块。
 *
 * 【为什么单独抽成一个 Bean,而不是放在 DocumentService 里】
 *
 * 这是 Spring 里一个非常经典的坑。@Transactional 是靠 AOP 代理实现的:
 * 外部调用会经过代理,代理负责开事务;但**类内部的方法互相调用不经过代理**,
 * 事务注解会静默失效 —— 不报错,只是没生效,极难排查。
 *
 * 所以凡是「需要事务的方法」,都要放在能被外部调用的 Bean 里。
 * 这里把索引逻辑单独放进 DocumentIndexService,由 DocumentService 从外部调用,
 * 事务才会真正生效。
 *
 * 顺带一个好处:第 12 项的「重新索引」接口可以直接复用这个类的 index 方法。
 */
@Service
public class DocumentIndexService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndexService.class);

    /** error_message 字段虽然不限长度,但没必要把一整段堆栈塞进数据库 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentStorage storage;
    private final DocumentTextReader textReader;
    private final TextSplitter textSplitter;
    private final EmbeddingService embeddingService;
    private final DocumentChunkVectorDao vectorDao;
    private final ObjectMapper objectMapper;
    private final ChunkIndexService chunkIndexService;
    private final KnowledgeBaseVersion knowledgeBaseVersion;

    public DocumentIndexService(DocumentRepository documentRepository,
                                DocumentChunkRepository chunkRepository,
                                DocumentStorage storage,
                                DocumentTextReader textReader,
                                TextSplitter textSplitter,
                                EmbeddingService embeddingService,
                                DocumentChunkVectorDao vectorDao,
                                ObjectMapper objectMapper,
                                ChunkIndexService chunkIndexService,
                                KnowledgeBaseVersion knowledgeBaseVersion) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.storage = storage;
        this.textReader = textReader;
        this.textSplitter = textSplitter;
        this.embeddingService = embeddingService;
        this.vectorDao = vectorDao;
        this.objectMapper = objectMapper;
        this.chunkIndexService = chunkIndexService;
        this.knowledgeBaseVersion = knowledgeBaseVersion;
    }

    /**
     * 把指定文档切分成块并写入数据库。
     *
     * 整个方法在一个事务里:读文件、删旧块、写新块、更新文档状态,
     * 要么全成功,要么全回滚。否则可能出现「块只写了一半,文档状态却是 INDEXED」的脏数据。
     *
     * @return 更新后的文档(已脱离持久化上下文,但字段值是事务提交后的最新值)
     */
    @Transactional
    public Document index(Long documentId) {
        long start = System.nanoTime();

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException("DOCUMENT_NOT_FOUND", "文档不存在:" + documentId));

        document.setStatus(DocumentStatus.INDEXING);

        // 1. 读文件 + 解析成纯文本。
        //    txt 走自研编码探测,pdf/docx/xlsx 走 Tika,分流在 DocumentTextReader 里。
        byte[] bytes = storage.read(document.getStoragePath());
        String text = textReader.read(bytes, document.getFileType(), document.getFileName());

        // 2. 切分。传入文件名(去扩展名)作为文档标题的兜底值 ——
        //    如果文档内部有自己的标题行,切分器会优先用内部的。
        List<TextChunk> textChunks = textSplitter.split(text, documentTitle(document));
        if (textChunks.isEmpty()) {
            throw new BusinessException("TEXT_EMPTY", "文档解析后没有任何有效文本");
        }

        // 3. 先清空旧分块。第 12 项重新索引时靠这一步避免新旧混杂。
        chunkRepository.deleteAllByDocumentId(documentId);

        // 4. 写入新分块
        List<DocumentChunk> chunks = new ArrayList<>(textChunks.size());
        for (int i = 0; i < textChunks.size(); i++) {
            TextChunk textChunk = textChunks.get(i);
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);
            chunk.setContent(textChunk.content());
            chunk.setMetadata(toMetadata(textChunk.sectionPath()));
            chunks.add(chunk);
        }
        // 单独计时:第 23 项要回答「IDENTITY 主键导致无法批量插入」到底值不值得优化。
        // 先测出这一段的真实耗时,再决定要不要为它换主键策略、加一次 Flyway 迁移。
        long insertStart = System.nanoTime();
        chunkRepository.saveAll(chunks);
        long insertMs = (System.nanoTime() - insertStart) / 1_000_000;

        // 5. 生成向量并回填。
        //
        // 注意:分块用的是 IDENTITY 主键,INSERT 会立刻执行,所以此时 chunks 里已经有 ID 了。
        // 换成 SEQUENCE 主键的话,Hibernate 可能把 INSERT 推迟到提交时批量执行,
        // 那时候这里就拿不到 ID,回填逻辑会失效 —— 这是个容易踩的联动点。
        List<float[]> vectors = embeddingService.embedAll(
                chunks.stream().map(DocumentChunk::getContent).toList());

        List<DocumentChunkVectorDao.ChunkVector> pending = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            pending.add(new DocumentChunkVectorDao.ChunkVector(chunks.get(i).getId(), vectors.get(i)));
        }
        vectorDao.updateEmbeddings(pending);

        // 6. 同步写入 Elasticsearch,供第 14 项的关键词召回使用。
        //    这里不放在事务外面,是因为 ES 写入失败应当让整次索引失败并回滚 ——
        //    与其留下「关系库里有、ES 里没有」的半成品,不如整篇重来。
        //
        //    【注意:必须先删旧数据,不能只靠 _id 覆盖】
        //
        //    ES 那边用 chunkId 作为文档 _id,重复写同一个 id 确实是覆盖。
        //    但重新索引时,数据库这边是先删旧分块再插新分块 ——
        //    新插进来的分块会拿到**全新的自增 id**。
        //    也就是说新旧两批数据的 chunkId 根本不一样,覆盖无从谈起,
        //    老的分块会一直留在索引里,造成检索结果里出现重复内容。
        //
        //    所以这里显式按 documentId 清一遍再写。
        chunkIndexService.deleteByDocumentId(document.getId());
        chunkIndexService.indexChunks(buildSearchDocuments(document, chunks, textChunks));

        // 7. 更新文档状态。
        // 这里不显式调用 save —— document 是从 repository 查出来的托管对象,
        // 修改字段后 Hibernate 会在事务提交时自动比对并发出 UPDATE,这叫「脏检查」。
        document.setChunkCount(chunks.size());
        document.setStatus(DocumentStatus.INDEXED);
        document.setErrorMessage(null);

        log.info("文档 {} 索引完成:{} 个分块,总耗时 {}ms(其中分块入库 {}ms)",
                documentId, chunks.size(), (System.nanoTime() - start) / 1_000_000, insertMs);

        // 知识库内容变了,把版本号加一 —— 所有旧的问答缓存立刻失效。
        // 不这么做的话,用户会继续拿到基于旧知识库生成的答案,而且完全看不出来。
        knowledgeBaseVersion.bump();

        return document;
    }

    private static List<ChunkDocument> buildSearchDocuments(Document document,
                                                            List<DocumentChunk> chunks,
                                                            List<TextChunk> textChunks) {
        List<ChunkDocument> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            documents.add(new ChunkDocument(
                    chunks.get(i).getId(),
                    document.getId(),
                    document.getFileName(),
                    i,
                    textChunks.get(i).sectionPath(),
                    chunks.get(i).getContent()));
        }
        return documents;
    }

    /**
     * 把文档标记为处理失败。
     *
     * 单独一个事务:调用它的时候 index() 的事务已经回滚了,
     * 失败标记必须写在事务之外才能落库。
     */
    @Transactional
    public void markFailed(Long documentId, String reason) {
        documentRepository.findById(documentId).ifPresent(document -> {
            document.setStatus(DocumentStatus.FAILED);
            document.setErrorMessage(truncate(reason, MAX_ERROR_MESSAGE_LENGTH));
            log.warn("文档 {} 处理失败:{}", documentId, reason);
        });
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** 文件名去掉扩展名,作为文档标题的兜底值 */
    private static String documentTitle(Document document) {
        String name = document.getFileName();
        if (name == null || name.isBlank()) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String toMetadata(String sectionPath) {
        if (sectionPath == null || sectionPath.isBlank()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(Map.of("section", sectionPath));
        } catch (JsonProcessingException ex) {
            // 元数据只是附加信息,序列化失败不该让整篇文档索引失败
            log.warn("章节路径序列化失败,该分块的 metadata 留空:{}", sectionPath, ex);
            return null;
        }
    }
}
