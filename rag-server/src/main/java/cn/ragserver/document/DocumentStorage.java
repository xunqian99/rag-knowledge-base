package cn.ragserver.document;

import cn.ragserver.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地文件存储。
 *
 * 单独抽出来是因为「文件放哪儿」和「文档怎么管理」是两件事:
 * 以后要换成 MinIO 或云对象存储,只改这个类,业务代码不动。
 */
@Component
public class DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorage.class);

    private final Path root;

    public DocumentStorage(DocumentProperties properties) {
        // toAbsolutePath + normalize 把 "uploads/../uploads" 这类写法规整掉,
        // 后面的越界检查才有意义。
        this.root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建文件存储根目录:" + root, ex);
        }
        log.info("文件存储根目录:{}", root);
    }

    /** 把上传的文件写入磁盘 */
    public void store(MultipartFile file, String relativePath) {
        Path target = resolveSafely(relativePath);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException ex) {
            throw new BusinessException("STORAGE_FAILED", "文件写入磁盘失败:" + ex.getMessage(), ex);
        }
    }

    /**
     * 尽力删除文件。
     *
     * 「尽力」的意思是:删不掉也不抛异常,只记日志。
     * 因为调用它时主流程已经因为别的原因失败了,
     * 清理动作再抛异常会把真正的失败原因盖掉,反而更难查。
     */
    public void deleteQuietly(String relativePath) {
        try {
            boolean deleted = Files.deleteIfExists(resolveSafely(relativePath));
            log.info("清理未完成上传的文件:{} 结果={}", relativePath, deleted ? "已删除" : "文件不存在");
        } catch (Exception ex) {
            log.warn("清理文件失败,路径:{}。该文件可能成为孤儿文件,需要人工或定时任务处理。", relativePath, ex);
        }
    }

    /**
     * 读取文件原始字节。
     *
     * 这里只负责「把字节读出来」,不负责判断编码 ——
     * 编码是文本处理的事,交给 TextSplitter。职责分开,以后换存储实现时也不用动解码逻辑。
     *
     * 用 readAllBytes 是因为本项目上传上限 50MB,而且是文本文件;
     * 如果要支持 GB 级文件,得改成流式读取。
     */
    public byte[] read(String relativePath) {
        Path target = resolveSafely(relativePath);
        try {
            return Files.readAllBytes(target);
        } catch (IOException ex) {
            throw new BusinessException("STORAGE_READ_FAILED", "读取文件失败:" + ex.getMessage(), ex);
        }
    }

    /**
     * 把相对路径解析成绝对路径,并确保它没有跑出存储根目录。
     *
     * 这是纵深防御:上游已经用 UUID 生成文件名,理论上不可能越界,
     * 但这里再兜一层 —— 万一以后有人改错了上游逻辑,这一层还能拦住。
     */
    private Path resolveSafely(String relativePath) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("INVALID_PATH", "非法的存储路径:" + relativePath);
        }
        return target;
    }
}
