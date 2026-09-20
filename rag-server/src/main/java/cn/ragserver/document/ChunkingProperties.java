package cn.ragserver.document;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分块策略配置,对应 application.yml 里的 rag.chunking。
 *
 * 这些数字不是拍脑袋定的,第 20 项会做参数对比实验来验证。
 * 现在先给一组对中文文档比较通用的起点。
 */
@ConfigurationProperties(prefix = "rag.chunking")
@Getter
@Setter
public class ChunkingProperties {

    /**
     * 分块最小字符数。
     * 不足这个长度的相邻段落会被合并 —— 太短的块缺少上下文,模型读不懂。
     */
    private int minSize = 150;

    /**
     * 分块最大字符数。
     * 超过这个长度的段落会被拆开 —— 太大的块里混了好几个主题,检索精度会下降。
     */
    private int maxSize = 500;

    /**
     * 相邻分块的重叠字符数。
     *
     * 作用是防止答案正好卡在块的边界上造成两边都缺失。
     * 代价是内容重复存储、索引膨胀,而且检索时可能命中多个高度重复的块,
     * 白白消耗 TOP K 的名额。
     *
     * 默认 0(关闭),留到第 19/20 项用评测数据决定要不要开、开多少。
     */
    private int overlap = 0;

    /**
     * 判定为标题的最大长度。
     *
     * 中文标题一般都很短。加这条限制是因为:正文里也可能出现
     * 「2.1 报销标准」这种编号,但它们往往以句号结尾而且很长,
     * 靠长度和结尾标点就能和真正的标题区分开。
     */
    private int maxHeadingLength = 40;
}
