package cn.ragserver.document;

import cn.ragserver.common.BusinessException;
import cn.ragserver.cache.KnowledgeBaseVersion;
import cn.ragserver.search.ChunkIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** 磁盘目录按日期分层:单目录下文件数量过多时,文件系统检索会明显变慢 */
    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** document.file_name 字段长度上限 */
    private static final int MAX_FILE_NAME_LENGTH = 255;

    /** 单页最多返回多少条,防止调用方传一个巨大的 size 把内存拉爆 */
    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentRepository repository;
    private final DocumentStorage storage;
    private final DocumentProperties properties;
    private final DocumentIndexService indexService;
    private final ChunkIndexService chunkIndexService;
    private final KnowledgeBaseVersion knowledgeBaseVersion;

    public DocumentService(DocumentRepository repository,
                           DocumentStorage storage,
                           DocumentProperties properties,
                           DocumentIndexService indexService,
                           ChunkIndexService chunkIndexService,
                           KnowledgeBaseVersion knowledgeBaseVersion) {
        this.repository = repository;
        this.storage = storage;
        this.properties = properties;
        this.indexService = indexService;
        this.chunkIndexService = chunkIndexService;
        this.knowledgeBaseVersion = knowledgeBaseVersion;
    }

    /**
     * 上传并建立索引:落盘 -> 入库 -> 切分。
     *
     * 分成两个事务:
     *   第一个事务(upload)负责「文件 + 文档记录」;
     *   第二个事务(indexService.index)负责「切分 + 分块记录 + 状态更新」。
     *
     * 为什么不合成一个大事务:合成一个的话,切分失败会把文档记录一起回滚,
     * 用户得重新上传一遍。分开之后,切分失败仍然留下一条 status=FAILED 的记录 ——
     * 第 12 项可以点「重新索引」重试,第 23 项也能自动重试。
     */
    public Document uploadAndIndex(MultipartFile file) {
        Document document = upload(file);
        return indexSafely(document.getId());
    }

    /**
     * 重新索引已有文档。
     *
     * 用途:改了分块策略、换了向量模型之后,已有文档需要按新规则重跑一遍。
     * 第 10 项就是靠它把已有文档切分升级的 —— 如果没有这个接口,
     * 改完切分策略只能把所有文件重新上传一遍。
     *
     * index 内部会先删掉旧分块再写新的,所以重复调用是安全的。
     */
    public Document reindex(Long documentId) {
        if (!repository.existsById(documentId)) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "文档不存在:" + documentId);
        }
        return indexSafely(documentId);
    }

    /**
     * 分页查询文档列表。按 id 倒序,最新上传的排在最前面。
     */
    public Page<Document> list(int page, int size) {
        int safePage = Math.max(0, page);
        // 给每页条数加硬上限。不加的话,调用方传个 size=100000
        // 就能让服务一次性把全部数据拉进内存 —— 很容易被忽略的防护点。
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return repository.findAll(PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")));
    }

    /**
     * 删除文档:数据库记录 + 磁盘文件。
     *
     * 【为什么这个方法刻意不加 @Transactional】
     *
     * 删除涉及数据库和文件系统两个系统,它们不可能在同一个事务里,
     * 所以顺序和时机都必须讲究:
     *
     *   1. 先删数据库记录 —— 数据库里没记录了,文件就成了「孤儿文件」,只是浪费磁盘
     *   2. 数据库提交成功之后,再删文件
     *
     * 反过来先删文件的话,一旦数据库操作失败回滚,库里就留下一条
     * 「记录还在、文件没了」的数据 —— 用户看得到却打不开,这是事故。
     *
     * 关键在于「提交之后」:如果给这个方法加上 @Transactional,
     * 删记录和删文件就进了同一个事务,而事务提交发生在方法返回之后 ——
     * 也就是删文件时事务还没提交,万一后面回滚,文件已经没了。
     *
     * 所以这里不加事务注解,让 repository.delete() 用它自己的事务立即提交。
     */
    public void delete(Long documentId) {
        Document document = repository.findById(documentId)
                .orElseThrow(() -> new BusinessException("DOCUMENT_NOT_FOUND", "文档不存在:" + documentId));

        String storagePath = document.getStoragePath();

        // 1. 先删 Elasticsearch 里的分块。
        //
        // 【注意这里的顺序和第 11 项的推理是反的,原因是"坏结果"不对称】
        //
        // 如果先删数据库、再删 ES,而 ES 删除失败:ES 里仍然留着已删文档的内容,
        // 用户能搜到、还会被喂给大模型 —— 这是数据泄漏,属于安全事故。
        //
        // 反过来先删 ES、数据库删除失败:只是 ES 少了一份数据,
        // 关键词检索会漏,用重新索引接口就能修好,属于质量问题。
        //
        // 宁可漏检索,不可泄漏。所以这一步放在最前面:
        // 它失败就直接中断,此时数据库和文件都还是完整的,用户重试即可。
        chunkIndexService.deleteByDocumentId(documentId);

        // 2. 删数据库记录。
        //    document_chunk 那边有 ON DELETE CASCADE(第 3 项建表时定的),
        //    它的所有分块会跟着一起删,这里不需要写任何代码。
        repository.delete(document);
        log.info("已删除文档记录:id={},file={}", documentId, document.getFileName());

        // 3. 数据库提交之后再删文件。删不掉只是留个孤儿文件,不影响正确性。
        storage.deleteQuietly(storagePath);

        // 4. 知识库内容变了,让所有问答缓存失效。
        //    删文档比加文档更需要这一步 —— 被删掉的可能是涉密内容,
        //    如果缓存还留着基于它生成的答案,那就是数据泄漏。
        knowledgeBaseVersion.bump();
    }

    private Document indexSafely(Long documentId) {
        try {
            return indexService.index(documentId);
        } catch (Exception ex) {
            // 索引失败不影响上传结果:文件已经落盘、记录也已经存在,只是还没处理成功。
            // 把失败原因记下来,让调用方从返回体的 status / errorMessage 里看到。
            indexService.markFailed(documentId, rootMessage(ex));
            return repository.findById(documentId)
                    .orElseThrow(() -> new BusinessException("DOCUMENT_NOT_FOUND", "文档不存在:" + documentId));
        }
    }

    public Document upload(MultipartFile file) {
        // ---------------- 1. 校验 ----------------
        if (file == null || file.isEmpty()) {
            throw new BusinessException("FILE_EMPTY", "上传的文件为空");
        }

        // 不管前端传什么文件名,先剥掉目录部分只留纯文件名
        String originalName = baseName(file.getOriginalFilename());
        if (originalName.isBlank()) {
            throw new BusinessException("FILE_NAME_INVALID", "文件名无效");
        }

        String extension = extensionOf(originalName);
        if (!properties.getAllowedExtensions().contains(extension)) {
            throw new BusinessException("FILE_TYPE_NOT_ALLOWED",
                    "不支持的文件类型 ." + extension + ",当前允许:" + properties.getAllowedExtensions());
        }

        // ---------------- 2. 生成磁盘路径 ----------------
        // 关键:磁盘文件名用随机 UUID,不用用户传来的名字。
        // 这样既杜绝了路径穿越(用户传 ..\..\Windows\x.txt),也不会重名覆盖。
        String relativePath = LocalDate.now().format(DATE_DIR) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + extension;

        // ---------------- 3. 先写盘 ----------------
        // 顺序很重要:先写盘、后入库。
        // 崩在中间只会多一个「没人引用的孤儿文件」(浪费磁盘);
        // 反过来崩则会留下「库里有记录但文件不存在」(用户看得到、打不开)。
        // 孤儿文件是垃圾,悬空引用是事故。
        storage.store(file, relativePath);
        log.info("文件已落盘:{} ({} 字节)", relativePath, file.getSize());

        // ---------------- 4. 再入库 ----------------
        Document document = new Document();
        document.setFileName(truncate(originalName, MAX_FILE_NAME_LENGTH));
        document.setFileType(extension);
        document.setFileSize(file.getSize());
        document.setStoragePath(relativePath);
        document.setStatus(DocumentStatus.PENDING);
        document.setChunkCount(0);

        try {
            // 用 saveAndFlush 而不是 save:
            // save 可能把真正的 INSERT 推迟到方法结束提交时才发生,失败点就不明确。
            // saveAndFlush 立即执行并提交,「数据库到底成没成功」在代码里有个清晰判断点。
            return repository.saveAndFlush(document);
        } catch (Exception ex) {
            cleanupAfterFailedInsert(relativePath, ex);
            throw new BusinessException("DATABASE_FAILED", "文档记录写入失败,本次上传已回滚", ex);
        }
    }

    /**
     * 入库失败后的补偿:删掉刚才落盘的文件。
     *
     * 但清理必须保守 —— 宁可漏删,不可误删。
     * 所以删之前先查一次库:确认真的没有这条记录才删,确认不了就不删。
     */
    private void cleanupAfterFailedInsert(String relativePath, Exception cause) {
        boolean referenced;
        try {
            referenced = repository.existsByStoragePath(relativePath);
        } catch (Exception queryEx) {
            // 连查询都失败了 -> 无法确认 -> 不删。
            // 留一个孤儿文件只是浪费磁盘;误删一个已被引用的文件才是真丢数据。
            log.error("入库失败后无法确认记录是否存在,已保留文件以便人工核对:{}", relativePath, queryEx);
            return;
        }

        if (referenced) {
            // 记录其实已经写进去了(异常发生在提交之后,比如连接断开)。
            // 这种情况绝不能删文件,否则库里会留下一条指向空文件的记录。
            log.warn("入库虽抛异常但记录已存在,保留文件:{}", relativePath);
            return;
        }

        storage.deleteQuietly(relativePath);
    }

    /**
     * 从原始文件名里取「纯文件名」,丢掉任何目录部分。
     *
     * 先把反斜杠统一成正斜杠,再取最后一个斜杠之后的内容 ——
     * 这样不依赖运行平台的路径规则,Windows 和 Linux 上行为一致。
     */
    private static String baseName(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        String normalized = originalFilename.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1).trim();
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** 取最内层异常的消息,避免把一堆包装异常的类名堆进数据库 */
    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
