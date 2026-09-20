package cn.ragserver.document;

import cn.ragserver.common.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 上传文档。
     *
     * 成功返回 201 Created 而不是 200 OK:语义上「创建了一个新资源」。
     * 也提示调用方这不是幂等操作 —— 重复上传会创建多条记录,不要盲目重试。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(@RequestParam("file") MultipartFile file) {
        Document document = documentService.uploadAndIndex(file);
        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentResponse.from(document));
    }

    /**
     * 重新索引:按当前的分块策略把文档重新切分一遍。
     *
     * 为什么是 POST 而不是 PUT:这个操作不是幂等的「替换资源」,
     * 而是「触发一次处理」。用 POST 更贴合语义。
     */
    @PostMapping("/{id}/reindex")
    public DocumentResponse reindex(@PathVariable Long id) {
        return DocumentResponse.from(documentService.reindex(id));
    }

    /**
     * 分页列出文档。默认第 0 页、每页 20 条,可以传 ?page=1&size=50 覆盖。
     */
    @GetMapping
    public PageResponse<DocumentResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(documentService.list(page, size), DocumentResponse::from);
    }

    /**
     * 删除文档。
     *
     * 返回 204 No Content 而不是 200:删除之后没有内容可以返回,
     * 这是 204 的标准语义,返回体为空。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        documentService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
