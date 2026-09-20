package cn.ragserver.eval;

import cn.ragserver.retrieval.HybridChunkRetriever;
import cn.ragserver.retrieval.RetrievalPipeline;
import cn.ragserver.retrieval.RetrievedChunk;
import cn.ragserver.retrieval.RrfFusion;
import cn.ragserver.retrieval.VectorChunkRetriever;
import cn.ragserver.search.EsChunkSearcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测执行器。
 *
 * 职责:读评测集 -> 对每种检索配置跑一遍 -> 算指标。
 *
 * 【为什么不做成 Web 接口】
 *
 * 150 题 x 4 种配置,预计要跑 5 分钟左右,瓶颈是每道题都要调一次 embedding 接口。
 * 放进 HTTP 请求里必然超时。所以做成命令行入口(见 EvaluationMain)。
 */
@Component
public class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

    /** 评测取前几条。Hit Rate@5 和 MRR 都基于这个 K。 */
    public static final int TOP_K = 5;

    private static final List<String> QUESTION_FILE_CANDIDATES = List.of(
            "eval/questions.jsonl",
            "../eval/questions.jsonl",
            "C:/Users/xunqian/Desktop/1/eval/questions.jsonl");

    private final ObjectMapper objectMapper;
    private final VectorChunkRetriever vectorRetriever;
    private final EsChunkSearcher bm25Retriever;
    private final HybridChunkRetriever hybridChunkRetriever;
    private final RrfFusion rrfFusion;
    private final RetrievalPipeline retrievalPipeline;

    public EvaluationRunner(ObjectMapper objectMapper,
                            VectorChunkRetriever vectorRetriever,
                            EsChunkSearcher bm25Retriever,
                            HybridChunkRetriever hybridChunkRetriever,
                            RrfFusion rrfFusion,
                            RetrievalPipeline retrievalPipeline) {
        this.objectMapper = objectMapper;
        this.vectorRetriever = vectorRetriever;
        this.bm25Retriever = bm25Retriever;
        this.hybridChunkRetriever = hybridChunkRetriever;
        this.rrfFusion = rrfFusion;
        this.retrievalPipeline = retrievalPipeline;
    }

    /**
     * 读取评测集。
     *
     * 会依次尝试几个候选路径 —— 因为从项目根目录运行和从 rag-server 目录运行,
     * 相对路径是不一样的,写死一个会在另一种情况下找不到文件。
     */
    public Path resolveQuestionFile() {
        for (String candidate : QUESTION_FILE_CANDIDATES) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) {
                return path;
            }
        }
        throw new IllegalStateException("找不到评测集文件,尝试过:" + QUESTION_FILE_CANDIDATES);
    }

    public List<EvaluationQuestion> loadQuestions() throws Exception {
        Path path = resolveQuestionFile();
        {
            List<EvaluationQuestion> questions = new ArrayList<>();
            for (String line : Files.readAllLines(path)) {
                if (!line.isBlank()) {
                    questions.add(objectMapper.readValue(line, EvaluationQuestion.class));
                }
            }
            log.info("已加载评测集:{} ({} 条)", path.toAbsolutePath(), questions.size());
            return questions;
        }
    }

    /**
     * 跑完全部四种配置,返回按配置分组的指标。
     */
    public Map<RetrievalMode, EvaluationMetrics> runAll(List<EvaluationQuestion> questions) {
        return runAll(questions, List.of(RetrievalMode.values()));
    }

    /**
     * 只跑指定的几种配置。
     *
     * 调参实验时会用到 —— 那是为了回答「哪个分块参数更好」,
     * 配置之间的对比在第 18 项已经做过了,没必要每次都重复跑四遍。
     */
    public Map<RetrievalMode, EvaluationMetrics> runAll(List<EvaluationQuestion> questions,
                                                        List<RetrievalMode> modes) {
        // 只评测可回答的题目。
        // 不可回答型没有关键短语,考察的是"系统会不会承认不知道",那是另一类指标,
        // 混进 Hit Rate 里只会污染数据。
        List<EvaluationQuestion> answerable = questions.stream()
                .filter(EvaluationQuestion::answerable)
                .toList();
        log.info("参与评测的题目数:{} (总 {} 条,排除不可回答型)", answerable.size(), questions.size());

        Map<RetrievalMode, EvaluationMetrics> result = new LinkedHashMap<>();
        for (RetrievalMode mode : modes) {
            result.put(mode, run(mode, answerable));
        }
        return result;
    }

    public EvaluationMetrics run(RetrievalMode mode, List<EvaluationQuestion> questions) {
        log.info("开始评测配置:{} ({} 题)", mode.label(), questions.size());

        EvaluationMetrics.Accumulator overall = new EvaluationMetrics.Accumulator();
        Map<String, EvaluationMetrics.Accumulator> byType = new LinkedHashMap<>();
        int done = 0;
        long start = System.nanoTime();

        for (EvaluationQuestion question : questions) {
            int rank = firstHitRank(question, retrieve(mode, question.question()));
            overall.add(rank);
            byType.computeIfAbsent(question.type(), key -> new EvaluationMetrics.Accumulator()).add(rank);

            done++;
            if (done % 25 == 0) {
                log.info("  {} 进度 {}/{}", mode.label(), done, questions.size());
            }
        }

        Map<String, EvaluationMetrics.TypeMetrics> typeMetrics = new LinkedHashMap<>();
        byType.forEach((type, accumulator) -> typeMetrics.put(type, accumulator.toMetrics()));

        EvaluationMetrics.TypeMetrics total = overall.toMetrics();
        log.info("配置 {} 完成:Hit@1={} Hit@{}={} MRR={} 耗时 {}s",
                mode.label(),
                percent(total.hit1Rate()), TOP_K, percent(total.hitRate()),
                String.format("%.4f", total.mrr()),
                (System.nanoTime() - start) / 1_000_000_000);

        return new EvaluationMetrics(mode, total.total(), total.hitCount(),
                total.hitRate(), total.hit1Rate(), total.mrr(), typeMetrics);
    }

    /**
     * 按配置执行一次检索。
     *
     * 四种配置正好对应项目演进的四个阶段,放在一起跑就能看出每一步的收益。
     */
    private List<RetrievedChunk> retrieve(RetrievalMode mode, String query) {
        return switch (mode) {
            case VECTOR -> vectorRetriever.retrieve(query, TOP_K);
            case BM25 -> bm25Retriever.retrieve(query, TOP_K);
            case HYBRID -> rrfFusion.fuse(hybridChunkRetriever.recall(query).channels())
                    .stream().limit(TOP_K).toList();
            case HYBRID_RERANK -> retrievalPipeline.retrieve(query, TOP_K).chunks();
        };
    }

    /**
     * 返回第一条命中的位置(从 1 开始),没命中返回 0。
     *
     * 命中判定:某一条分块的内容里包含这道题的全部关键短语。
     */
    private static int firstHitRank(EvaluationQuestion question, List<RetrievedChunk> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            if (containsAll(chunks.get(i).content(), question.keyPhrases())) {
                return i + 1;
            }
        }
        return 0;
    }

    private static boolean containsAll(String content, List<String> keyPhrases) {
        if (keyPhrases == null || keyPhrases.isEmpty()) {
            return false;
        }
        for (String phrase : keyPhrases) {
            if (!content.contains(phrase)) {
                return false;
            }
        }
        return true;
    }

    private static String percent(double value) {
        return String.format("%.1f%%", value * 100);
    }

    /**
     * 把结果渲染成 Markdown 表格。
     */
    public static String renderReport(Map<RetrievalMode, EvaluationMetrics> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 检索效果评测报告\n\n");

        sb.append("## 总体\n\n");
        sb.append("| 配置 | 题目数 | 命中数 | Hit@1 | Hit@").append(TOP_K).append(" | MRR |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (EvaluationMetrics m : results.values()) {
            sb.append("| ").append(m.mode().label())
                    .append(" | ").append(m.total())
                    .append(" | ").append(m.hitCount())
                    .append(" | ").append(percent(m.hit1Rate()))
                    .append(" | ").append(percent(m.hitRate()))
                    .append(" | ").append(String.format("%.4f", m.mrr()))
                    .append(" |\n");
        }

        List<String> types = results.values().stream()
                .flatMap(m -> m.byType().keySet().stream())
                .distinct()
                .toList();

        sb.append("\n## 按题型 —— Hit@1\n\n");
        sb.append("| 配置 |").append(String.join("", types.stream().map(t -> " " + t + " |").toList())).append("\n");
        sb.append("|---|").append("---|".repeat(types.size())).append("\n");
        for (EvaluationMetrics m : results.values()) {
            sb.append("| ").append(m.mode().label()).append(" |");
            for (String type : types) {
                EvaluationMetrics.TypeMetrics t = m.byType().get(type);
                sb.append(' ').append(t == null ? "-" : percent(t.hit1Rate())).append(" |");
            }
            sb.append("\n");
        }

        sb.append("\n## 按题型 —— Hit@").append(TOP_K).append("\n\n");
        sb.append("| 配置 |").append(String.join("", types.stream().map(t -> " " + t + " |").toList())).append("\n");
        sb.append("|---|").append("---|".repeat(types.size())).append("\n");
        for (EvaluationMetrics m : results.values()) {
            sb.append("| ").append(m.mode().label()).append(" |");
            for (String type : types) {
                EvaluationMetrics.TypeMetrics t = m.byType().get(type);
                sb.append(' ').append(t == null ? "-" : percent(t.hitRate())).append(" |");
            }
            sb.append("\n");
        }

        sb.append("\n## 按题型 —— MRR\n\n");
        sb.append("| 配置 |").append(String.join("", types.stream().map(t -> " " + t + " |").toList())).append("\n");
        sb.append("|---|").append("---|".repeat(types.size())).append("\n");
        for (EvaluationMetrics m : results.values()) {
            sb.append("| ").append(m.mode().label()).append(" |");
            for (String type : types) {
                EvaluationMetrics.TypeMetrics t = m.byType().get(type);
                sb.append(' ').append(t == null ? "-" : String.format("%.4f", t.mrr())).append(" |");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
