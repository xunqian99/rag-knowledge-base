package cn.ragserver.document;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本分块。
 *
 * 【这一版和第 6 项的朴素版有什么不同】
 *
 * 朴素版只按空行切段落,留下了三个实测问题(见决策记录 006 / 008 / 009):
 *   1. 光秃秃的标题块没有正文,相似度却排得很高
 *   2. 块粒度极不均匀:txt 平均 55 字,pdf 平均 26 字,xlsx 整表 304 字一块
 *   3. 标题与其正文分离,导致彼此都缺半截语义
 *
 * 这一版的做法:
 *   识别标题 -> 维护标题栈 -> 正文按章节聚合 -> 加面包屑前缀 -> 长度约束打包
 *
 * 结果:标题不再单独成块,而是变成每一个正文块的「所属章节」前缀。
 */
@Component
public class TextSplitter {

    /** 第X章 / 第X编 / 第X篇 */
    private static final Pattern CHAPTER = Pattern.compile("^第[一二三四五六七八九十百零〇\\d]+[章编篇].*");

    /** 第X节 */
    private static final Pattern SECTION = Pattern.compile("^第[一二三四五六七八九十百零〇\\d]+节.*");

    /** 1.1 / 1.1.1 这种多级编号 */
    private static final Pattern NUMBERED = Pattern.compile("^\\d+(\\.\\d+)+[\\s、.].*");

    /** (一)(二) 这种中文小标题 */
    private static final Pattern BRACKETED = Pattern.compile("^[（(][一二三四五六七八九十\\d]+[)）].*");

    /** Markdown 标题 */
    private static final Pattern MARKDOWN = Pattern.compile("^(#{1,6})\\s+.*");

    /** 用于提取多级编号的前缀部分,以便算出层级 */
    private static final Pattern NUMBER_PREFIX = Pattern.compile("^(\\d+(?:\\.\\d+)+)");

    /** 中文句子边界。放在句末标点之后切开,标点本身保留在前一句末尾。 */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。!?;!?;])");

    private static final String BREADCRUMB_SEPARATOR = " > ";

    private final ChunkingProperties properties;

    public TextSplitter(ChunkingProperties properties) {
        this.properties = properties;
    }

    /**
     * @param text          解析出来的纯文本
     * @param fallbackTitle 文档标题的兜底值(通常用文件名去掉扩展名)
     */
    public List<TextChunk> split(String text, String fallbackTitle) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = List.of(normalized.split("\n", -1));

        // ---------------- 1. 确定文档标题 ----------------
        // 文档第一行通常就是标题。判据:短、而且不符合任何标题格式
        //(如果它本身是「第一章 xxx」,那就不是文档标题而是章节标题)。
        String title = fallbackTitle;
        int cursor = 0;
        int firstNonBlank = firstNonBlankLine(lines);
        if (firstNonBlank >= 0) {
            String first = lines.get(firstNonBlank).strip();
            if (!first.isEmpty() && first.length() <= properties.getMaxHeadingLength() && headingLevel(first) == 0) {
                title = first;
                cursor = firstNonBlank + 1;
            }
        }

        // ---------------- 2. 扫描成「标题」与「正文段」两类单元 ----------------
        List<Unit> units = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (int i = cursor; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw.isBlank()) {
                flushContent(buffer, units);
                continue;
            }
            String line = raw.strip();
            int level = headingLevel(line);
            if (level > 0) {
                // 标题前面若还攒着正文,先收尾
                flushContent(buffer, units);
                units.add(new Unit(line, level));
            } else {
                if (buffer.length() > 0) {
                    buffer.append('\n');
                }
                buffer.append(line);
            }
        }
        flushContent(buffer, units);

        if (units.isEmpty()) {
            return List.of();
        }

        // ---------------- 3. 按章节聚合正文 ----------------
        // 用 LinkedHashMap 而不是 HashMap:要保证分块顺序与原文一致,
        // 否则 chunk_index 就乱了,引用溯源也没法按顺序展示。
        Map<String, List<String>> sectionParagraphs = new LinkedHashMap<>();
        Deque<Heading> stack = new ArrayDeque<>();
        for (Unit unit : units) {
            if (unit.isHeading()) {
                pushHeading(stack, unit);
                continue;
            }
            String path = breadcrumb(title, stack);
            sectionParagraphs.computeIfAbsent(path, key -> new ArrayList<>()).add(unit.text());
        }

        // ---------------- 4. 每个章节内部做长度约束的打包 ----------------
        List<TextChunk> chunks = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : sectionParagraphs.entrySet()) {
            String path = entry.getKey();
            for (String body : packSection(entry.getValue())) {
                String content = path.isEmpty() ? body : "【" + path + "】\n" + body;
                chunks.add(new TextChunk(content, path));
            }
        }
        return chunks;
    }

    // ------------------------------------------------------------------
    // 长度约束的打包
    // ------------------------------------------------------------------

    private List<String> packSection(List<String> paragraphs) {
        // 整个章节就是一整张表的情况单独处理:按行拆,并把表头复制到每一块
        if (paragraphs.size() == 1 && isTable(paragraphs.get(0))) {
            return packTable(paragraphs.get(0));
        }

        int max = properties.getMaxSize();
        int min = properties.getMinSize();

        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String paragraph : paragraphs) {
            for (String atom : explode(paragraph)) {
                if (current.length() > 0 && current.length() + atom.length() + 1 > max) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(atom);
            }
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }

        return applyOverlap(mergeSmall(pieces, min, max));
    }

    /**
     * 把过长的段落拆成不超过上限的原子片段。
     *
     * 拆分顺序是「逐级降级」的:
     *   整段太短 -> 原样返回
     *   按换行拆(表格行天然在这里分开)
     *   单行还太长 -> 按中文句末标点拆
     *   单句还太长 -> 只能按固定长度硬切
     *
     * 越靠前的策略保留的语义越完整。
     */
    private List<String> explode(String paragraph) {
        int max = properties.getMaxSize();
        if (paragraph.length() <= max) {
            return List.of(paragraph);
        }

        List<String> atoms = new ArrayList<>();
        for (String line : paragraph.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (line.length() <= max) {
                atoms.add(line);
            } else {
                atoms.addAll(splitSentences(line, max));
            }
        }
        return atoms.isEmpty() ? List.of(paragraph) : atoms;
    }

    private static List<String> splitSentences(String line, int max) {
        List<String> result = new ArrayList<>();
        for (String sentence : SENTENCE_BOUNDARY.split(line)) {
            if (sentence.isBlank()) {
                continue;
            }
            if (sentence.length() <= max) {
                result.add(sentence);
                continue;
            }
            // 连一句都超长(比如没有标点的长串),只能硬切
            for (int from = 0; from < sentence.length(); from += max) {
                result.add(sentence.substring(from, Math.min(from + max, sentence.length())));
            }
        }
        return result;
    }

    /**
     * 合并过短的块。
     *
     * 只在「合并后不超过上限」时才合并,避免为了凑长度反而制造出超大块。
     */
    private static List<String> mergeSmall(List<String> pieces, int min, int max) {
        List<String> result = new ArrayList<>();
        for (String piece : pieces) {
            if (!result.isEmpty() && piece.length() < min) {
                int lastIndex = result.size() - 1;
                String last = result.get(lastIndex);
                if (last.length() + piece.length() + 1 <= max) {
                    result.set(lastIndex, last + "\n" + piece);
                    continue;
                }
            }
            result.add(piece);
        }
        return result;
    }

    /**
     * 相邻块之间加上重叠内容。
     *
     * 默认关闭(overlap=0)。实现放在这里是为了第 19/20 项做对比实验时,
     * 只需要改一个配置项就能开起来,不用再改代码。
     */
    private List<String> applyOverlap(List<String> pieces) {
        int overlap = properties.getOverlap();
        if (overlap <= 0 || pieces.size() < 2) {
            return pieces;
        }
        List<String> result = new ArrayList<>(pieces.size());
        result.add(pieces.get(0));
        for (int i = 1; i < pieces.size(); i++) {
            String previous = pieces.get(i - 1);
            String head = previous.length() <= overlap
                    ? previous
                    : previous.substring(previous.length() - overlap);
            result.add(head + pieces.get(i));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 表格
    // ------------------------------------------------------------------

    /**
     * 判断是不是表格:行数够多,而且几乎每一行都有多个制表符。
     *
     * Tika 解析 Excel 时用制表符分隔单元格,所以这个判据对本项目够用。
     */
    private static boolean isTable(String block) {
        String[] lines = block.split("\n");
        if (lines.length < 3) {
            return false;
        }
        int tabbed = 0;
        for (String line : lines) {
            if (countTabs(line) >= 2) {
                tabbed++;
            }
        }
        return tabbed >= lines.length - 1;
    }

    /**
     * 表格按行拆,并给每一块都带上表头。
     *
     * 为什么要重复表头:拆开之后单独一行是「客户招待 | 单次不超过 1500 元 | 是」——
     * 没有表头的话,检索到这一块时完全不知道三列分别是什么意思。
     */
    private List<String> packTable(String block) {
        int max = properties.getMaxSize();
        String[] lines = block.split("\n");
        String header = lines[0];

        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            String row = lines[i];
            if (current.length() > 0 && current.length() + row.length() + 1 > max) {
                pieces.add(header + "\n" + current);
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(row);
        }
        if (current.length() > 0) {
            pieces.add(header + "\n" + current);
        }
        return pieces.isEmpty() ? List.of(block) : pieces;
    }

    private static int countTabs(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '\t') {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 标题识别
    // ------------------------------------------------------------------

    /**
     * 判断某一行是不是标题,是则返回层级(越小级别越高),不是返回 0。
     *
     * 两个必要条件缺一不可:
     *   1. 长度不超过上限 —— 正文里也有「1.1」这样的编号,但它们后面跟着很长的句子
     *   2. 不以句末标点结尾 —— 标题不会用句号收尾
     */
    private int headingLevel(String line) {
        if (line.isEmpty() || line.length() > properties.getMaxHeadingLength()) {
            return 0;
        }
        if (endsWithSentencePunctuation(line)) {
            return 0;
        }
        if (CHAPTER.matcher(line).matches()) {
            return 1;
        }
        if (SECTION.matcher(line).matches()) {
            return 2;
        }
        Matcher markdown = MARKDOWN.matcher(line);
        if (markdown.matches()) {
            return Math.min(markdown.group(1).length(), 4);
        }
        if (NUMBERED.matcher(line).matches()) {
            Matcher prefix = NUMBER_PREFIX.matcher(line);
            if (prefix.find()) {
                // 1.1 是两级 -> 层级 2;1.1.1 是三级 -> 层级 3
                return Math.min(prefix.group(1).split("\\.").length, 4);
            }
        }
        if (BRACKETED.matcher(line).matches()) {
            return 3;
        }
        return 0;
    }

    private static boolean endsWithSentencePunctuation(String line) {
        char last = line.charAt(line.length() - 1);
        return last == '。' || last == '.'
                || last == '!' || last == '!'
                || last == '?' || last == '?'
                || last == ';' || last == ';'
                || last == ':' || last == ':';
    }

    /**
     * 把标题压入栈。
     *
     * 新标题会把所有「层级不低于自己」的旧标题弹掉。
     * 比如遇到「第二章」时,之前压进去的「第一章」和它下面的「1.1」「1.2」都要出栈,
     * 面包屑才不会串到上一个章节去。
     */
    private static void pushHeading(Deque<Heading> stack, Unit unit) {
        while (!stack.isEmpty() && stack.peek().level() >= unit.level()) {
            stack.pop();
        }
        stack.push(new Heading(unit.text(), unit.level()));
    }

    private static String breadcrumb(String title, Deque<Heading> stack) {
        List<String> parts = new ArrayList<>();
        if (title != null && !title.isBlank()) {
            parts.add(title);
        }
        // ArrayDeque 的迭代顺序是「栈顶 -> 栈底」,要反转成从大到小
        List<Heading> ordered = new ArrayList<>(stack);
        Collections.reverse(ordered);
        for (Heading heading : ordered) {
            parts.add(heading.text());
        }
        return String.join(BREADCRUMB_SEPARATOR, parts);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static void flushContent(StringBuilder buffer, List<Unit> units) {
        if (buffer.length() > 0) {
            units.add(new Unit(buffer.toString(), 0));
            buffer.setLength(0);
        }
    }

    private static int firstNonBlankLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                return i;
            }
        }
        return -1;
    }

    /** 扫描阶段的单元:level 为 0 表示正文段,大于 0 表示标题 */
    private record Unit(String text, int level) {
        boolean isHeading() {
            return level > 0;
        }
    }

    private record Heading(String text, int level) {
    }
}
