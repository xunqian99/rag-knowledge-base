package cn.ragserver.document;

import cn.ragserver.common.BusinessException;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 把上传的文件变成纯文本。所有格式的入口都在这里。
 *
 * 【为什么按格式分成两条路,而不是一律交给 Tika】
 *
 * Tika 对纯文本的默认假设是 UTF-8(除非文件带 BOM),
 * 而 Windows 上的中文 txt 有相当一部分是 GBK/GB18030 —— 交给 Tika 会乱码。
 *
 * 我们自己在第 6 项已经验证过「严格 UTF-8 -> 回退 GB18030」这套探测是有效的,
 * 那就让 txt 继续走这条路。其余格式的复杂度远超自己实现的范围,交给 Tika。
 *
 * 这不是偷懒,是因为两条路各自的强项不同。
 *
 * 【关于「为什么不用接口 + 多个实现」】
 *
 * 前面 DependencyChecker 用了接口 + 集合注入,是因为探测器数量是开放的,
 * 以后随时可能加新的依赖。而这里的格式分流只有两条固定分支,
 * 为固定分支造接口反而绕。**接口是为了应对未知数量的实现,不是为了形式上的解耦。**
 */
@Component
public class DocumentTextReader {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextReader.class);

    /** 走自研解码的纯文本格式 */
    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of("txt");

    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final DocumentProperties properties;

    /**
     * Tika 的门面对象。
     *
     * 它是线程安全的,可以复用为单例,不必每次解析都 new 一个 ——
     * 每次 new 都要重新初始化格式探测器和解析器注册表,开销不小。
     */
    private final Tika tika = new Tika();

    public DocumentTextReader(DocumentProperties properties) {
        this.properties = properties;
    }

    /**
     * @param bytes     文件原始字节
     * @param extension 小写扩展名
     * @param fileName  原始文件名,仅用于日志
     */
    public String read(byte[] bytes, String extension, String fileName) {
        if (bytes.length == 0) {
            throw new BusinessException("FILE_EMPTY", "文件内容为空");
        }

        String text = PLAIN_TEXT_EXTENSIONS.contains(extension)
                ? decodePlainText(bytes)
                : parseWithTika(bytes, fileName);

        checkLength(text, fileName);
        return text;
    }

    // ------------------------------------------------------------------
    // 路线一:纯文本,自研编码探测
    // ------------------------------------------------------------------

    /**
     * 顺序:去掉 BOM -> 严格 UTF-8 -> 回退 GB18030 -> 都失败则明确报错。
     *
     * 关键在「严格」两个字:宽松模式会把非法字节静默替换成 U+FFFD,
     * 程序不报错,但内容已经坏了,反而更难发现。
     */
    private String decodePlainText(byte[] bytes) {
        if (startsWithUtf8Bom(bytes)) {
            return new String(bytes, UTF8_BOM.length, bytes.length - UTF8_BOM.length, StandardCharsets.UTF_8);
        }

        try {
            return decodeStrict(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException ex) {
            log.debug("文件不是合法 UTF-8,改用 GB18030 解码");
        }

        try {
            return decodeStrict(bytes, GB18030);
        } catch (CharacterCodingException ex) {
            throw new BusinessException("TEXT_DECODE_FAILED",
                    "无法识别文件编码,目前支持 UTF-8 与 GB18030");
        }
    }

    private static String decodeStrict(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static boolean startsWithUtf8Bom(byte[] bytes) {
        if (bytes.length < UTF8_BOM.length) {
            return false;
        }
        for (int i = 0; i < UTF8_BOM.length; i++) {
            if (bytes[i] != UTF8_BOM[i]) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 路线二:其他格式,交给 Tika
    // ------------------------------------------------------------------

    /**
     * 注意这里没有把文件名传给 Tika。
     *
     * Tika 支持通过 Metadata 传入 RESOURCE_NAME_KEY 来辅助格式判断,
     * 但那样等于「相信用户给的名字」;而它按文件头自带探测已经足够准,
     * 把木马改名成 .pdf 传上来它照样能识破。
     *
     * 直接传字节、让 Tika 自己判断,更安全也更简单。
     */
    private String parseWithTika(byte[] bytes, String fileName) {
        // maxLength 用 int,把配置的 long 上限收窄一下
        int maxChars = (int) Math.min(properties.getMaxTextLength(), Integer.MAX_VALUE);

        try (InputStream in = new ByteArrayInputStream(bytes)) {
            // 传 maxLength 是让 Tika 在读到上限时就停下并返回已解析的部分,
            // 而不是把整个文件读进内存后再判断 —— 后者对解压炸弹毫无防备。
            return tika.parseToString(in, new org.apache.tika.metadata.Metadata(), maxChars);
        } catch (Exception ex) {
            // 解析失败的原因可能很多(文件损坏、加密、格式伪装),
            // 这些属于用户输入问题,返回 400 而不是 500。
            log.warn("解析文件失败:{}", fileName, ex);
            throw new BusinessException("PARSE_FAILED",
                    "文件解析失败,可能是文件损坏或格式不受支持:" + ex.getMessage(), ex);
        }
    }

    private void checkLength(String text, String fileName) {
        if (text.length() > properties.getMaxTextLength()) {
            throw new BusinessException("TEXT_TOO_LONG",
                    "解析出的文本超过上限 " + properties.getMaxTextLength() + " 字符,请拆分后再上传");
        }
    }
}
