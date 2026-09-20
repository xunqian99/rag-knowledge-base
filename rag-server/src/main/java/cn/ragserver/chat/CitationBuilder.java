package cn.ragserver.chat;

import cn.ragserver.retrieval.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用来源的组装。
 *
 * 抽出来是因为非流式和流式两条链路都要用同一套逻辑 ——
 * 如果各写一份,两边的"引用该返哪些"判断一旦不一致,
 * 用户会看到同一个问题在两种调用方式下给出不同的引用,很难解释。
 */
@Component
public class CitationBuilder {

    private static final Logger log = LoggerFactory.getLogger(CitationBuilder.class);

    /** 引用片段展示多长,太长前端列表放不下 */
    private static final int SNIPPET_LENGTH = 80;

    /**
     * 匹配答案里的来源编号,例如 [1]、[2]。
     *
     * 之所以要解析而不是「把检索到的全都当成引用返回」:
     * 检索会固定返回 TOP K 条,但模型实际只用了其中一两条。
     * 用户点开一条压根没被使用的引用,会以为系统在乱指 —— 引用溯源的可信度就没了。
     */
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[(\\d+)]");

    /**
     * 组装候选引用。编号必须和 prompt 里的 [1][2] 严格对应。
     */
    public List<AnswerResponse.Citation> buildCandidates(List<RetrievedChunk> chunks) {
        List<AnswerResponse.Citation> candidates = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            candidates.add(new AnswerResponse.Citation(
                    i + 1,
                    chunk.chunkId(),
                    chunk.documentId(),
                    chunk.fileName(),
                    chunk.chunkIndex(),
                    round4(chunk.score()),
                    abbreviate(chunk.content())));
        }
        return candidates;
    }

    /**
     * 从答案正文里解析出 [1][2] 这类标记,只返回被点到的引用。
     *
     * 用 LinkedHashSet 是为了既去重、又保持出现顺序 ——
     * 答案里先提到 [2] 再提到 [1],返回的引用列表也该是这个顺序。
     */
    public List<AnswerResponse.Citation> keepCitedOnly(
            List<AnswerResponse.Citation> candidates, String answer) {
        Set<Integer> usedIndexes = new LinkedHashSet<>();
        Matcher matcher = CITATION_MARKER.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            usedIndexes.add(Integer.parseInt(matcher.group(1)));
        }

        if (usedIndexes.isEmpty()) {
            // 模型没有标注来源。可能它判定资料不足(例如回答「没有找到相关内容」),
            // 也可能是它没按格式输出。记一条日志,方便回头调 prompt。
            log.debug("答案中没有来源编号标记,不返回引用列表");
            return List.of();
        }

        return candidates.stream()
                .filter(citation -> usedIndexes.contains(citation.index()))
                .toList();
    }

    /** 引用片段:压平换行并截断,方便前端列表展示 */
    public String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= SNIPPET_LENGTH ? flat : flat.substring(0, SNIPPET_LENGTH) + "...";
    }

    /** 日志里用的短摘要,比引用片段更短 */
    public String brief(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= maxLength ? flat : flat.substring(0, maxLength) + "...";
    }

    private static double round4(double value) {
        return Math.round(value * 10000) / 10000.0;
    }
}
