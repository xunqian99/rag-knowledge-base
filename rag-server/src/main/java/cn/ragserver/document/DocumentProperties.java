package cn.ragserver.document;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 文件存储相关配置,对应 application.yml 里的 rag.storage 前缀。
 *
 * 用 @ConfigurationProperties 而不是散落的 @Value:
 * 配置成组、有类型、启动时就绑定,写错了立刻失败,而不是等运行到那一行才发现。
 */
@ConfigurationProperties(prefix = "rag.storage")
@Getter
@Setter
public class DocumentProperties {

    /** 文件存储根目录,相对于应用的运行目录 */
    private String root = "uploads";

    /**
     * 允许上传的扩展名白名单。
     *
     * 用白名单而不是黑名单:黑名单永远列不全,白名单只要没写进去就一定拦得住。
     * 第 9 项接入 Tika 支持 PDF/Word/Excel 时,只需要在这里加几个后缀。
     */
    private Set<String> allowedExtensions = new LinkedHashSet<>(Set.of("txt"));

    /**
     * 解析出的文本长度上限(字符数)。
     *
     * 【这不是拍脑袋写的数字,是安全防线】
     *
     * 一个 100KB 的 docx 解压后可能是几个 GB 的 XML —— 这叫「解压炸弹」。
     * 解析库默认会把内容全部读进内存,足以把服务打挂。
     * 所以必须设上限,超了就明确拒绝,而不是硬扛。
     */
    private long maxTextLength = 5_000_000L;
}
