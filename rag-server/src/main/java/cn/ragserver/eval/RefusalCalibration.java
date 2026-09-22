package cn.ragserver.eval;

import cn.ragserver.retrieval.RetrievalPipeline;
import cn.ragserver.retrieval.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 拒答阈值校准。
 *
 * 【要解决的问题】
 *
 * 现在只要检索回来非空,链路就会调用一次大模型 —— 哪怕知识库里根本没有答案。
 * 问「年会什么时候举行」时,系统会认真地把 3 条无关资料塞进 prompt、花掉一次
 * 模型调用,最后靠系统指令让模型输出「资料中没有找到相关内容」。
 *
 * 如果精排分数能提前告诉我们「这批候选都不相关」,这次调用就能省掉。
 * 问题在于 **阈值定多少**:定低了会把能回答的问题也拒掉,定高了又拦不住。
 * 这个数字不能拍脑袋,得用评测数据算出来 —— 这就是这个类干的事。
 *
 * 【两类题目,两套指标,绝不能混算】
 *
 *   不可回答题 -> **误答率**:分数达到阈值就会被回答,而知识库里根本没有答案
 *   可回答题   -> **误拒率**:分数低于阈值会被拒答,而问题本来答得出来
 *
 * 注意「误拒」只统计那些 **top-1 就命中** 的题目。
 * 如果检索本身就没找到答案,拒答反而是正确行为,不该算作阈值的代价。
 * 这决定了阈值的收益上限:能避免的错答次数,顶多是「top-1 未命中」的题目数。
 *
 * 【为什么要做留出验证】
 *
 * 阈值是从这批数据里挑出来的,直接在挑它的那批数据上报成绩就是过拟合 ——
 * 相当于同一套题既出题又判分。所以按题号奇偶切两半:
 * 偶数题挑阈值,奇数题只用于复核。两边的差距就是过拟合程度的直接证据。
 */
@Component
public class RefusalCalibration {

    private static final Logger log = LoggerFactory.getLogger(RefusalCalibration.class);

    /**
     * 挑阈值时允许的最大误答率。
     *
     * 取 5% 是因为两类错误的代价不对称:误答会让用户看到一个编造的答案并信以为真,
     * 误拒只是让用户换个说法再问一次。所以先把误答率压到可接受的水平,
     * 在这个前提下去优化误拒率 —— 而不是给两个错误各配一个权重再求最小值,
     * 那种权重同样是拍脑袋,还不好解释。
     */
    private static final double MAX_FALSE_ANSWER_RATE = 0.05;

    /** 展示用的阈值刻度。挑阈值时用的是观测到的全部候选值,这里只是为了让表格好读。 */
    private static final double[] DISPLAY_THRESHOLDS = {0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5};

    private final RetrievalPipeline retrievalPipeline;

    public RefusalCalibration(RetrievalPipeline retrievalPipeline) {
        this.retrievalPipeline = retrievalPipeline;
    }

    /** 一道题的观测结果:top-1 分数是多少,以及 top-1 是否已经命中答案。 */
    private record Obs(boolean answerable, boolean tuneSet, double top1, boolean top1Hit) {
    }

    /**
     * 某个阈值下的统计。
     *
     * @param falseAnswer  不可回答题里分数达到阈值、会被回答的数量
     * @param unanswerable 不可回答题总数
     * @param falseRefuse  本来答得对、却会被拒答的数量
     * @param answerableHit 本来答得对的总数(top-1 命中)
     * @param avoidedWrong 检索本就没找到答案、被阈值拦下的数量(这是阈值的收益)
     * @param answerableMiss 检索本就没找到答案的总数
     */
    public record Row(double threshold,
                      int falseAnswer, int unanswerable,
                      int falseRefuse, int answerableHit,
                      int avoidedWrong, int answerableMiss) {

        public double falseAnswerRate() {
            return unanswerable == 0 ? 0 : (double) falseAnswer / unanswerable;
        }

        public double falseRefuseRate() {
            return answerableHit == 0 ? 0 : (double) falseRefuse / answerableHit;
        }
    }

    public record Result(List<Row> curve,
                         Row chosenOnTune,
                         int tuneUnanswerable, int tuneAnswerableHit,
                         int holdoutUnanswerable, int holdoutAnswerableHit,
                         int holdoutFalseAnswer, int holdoutFalseRefuse,
                         int answerableHitTotal, int answerableMissTotal) {

        public double holdoutFalseAnswerRate() {
            return holdoutUnanswerable == 0 ? 0 : (double) holdoutFalseAnswer / holdoutUnanswerable;
        }

        public double holdoutFalseRefuseRate() {
            return holdoutAnswerableHit == 0 ? 0 : (double) holdoutFalseRefuse / holdoutAnswerableHit;
        }
    }

    public Result calibrate(List<EvaluationQuestion> questions) {
        List<Obs> obs = new ArrayList<>();
        for (EvaluationQuestion question : questions) {
            // topN 取 1:拿到的就是和正式问答链路一致的精排 top-1 分数。
            List<RetrievedChunk> top = retrievalPipeline.retrieve(question.question(), 1).chunks();
            double top1 = top.isEmpty() ? 0 : top.get(0).score();
            boolean hit = !top.isEmpty() && containsAll(top.get(0).content(), question.keyPhrases());
            obs.add(new Obs(question.answerable(), isTuneSet(question.id()), top1, hit));
        }

        List<Obs> tune = obs.stream().filter(Obs::tuneSet).toList();
        List<Obs> holdout = obs.stream().filter(o -> !o.tuneSet()).toList();

        List<Row> curve = new ArrayList<>();
        for (double threshold : DISPLAY_THRESHOLDS) {
            curve.add(stat(obs, threshold));
        }

        Row chosenOnTune = pick(tune);
        Row chosenOnHoldout = stat(holdout, chosenOnTune.threshold());

        Row all = stat(obs, chosenOnTune.threshold());
        log.info("拒答阈值校准完成:选定阈值 {} ,调参集上误答率 {} 误拒率 {} ;验证集上误答率 {} 误拒率 {}",
                round(chosenOnTune.threshold()),
                percent(chosenOnTune.falseAnswerRate()), percent(chosenOnTune.falseRefuseRate()),
                percent(chosenOnHoldout.falseAnswerRate()), percent(chosenOnHoldout.falseRefuseRate()));

        return new Result(curve,
                chosenOnTune,
                tune.size() - (int) tune.stream().filter(Obs::answerable).count(),
                (int) tune.stream().filter(o -> o.answerable() && o.top1Hit()).count(),
                holdout.size() - (int) holdout.stream().filter(Obs::answerable).count(),
                (int) holdout.stream().filter(o -> o.answerable() && o.top1Hit()).count(),
                chosenOnHoldout.falseAnswer(),
                chosenOnHoldout.falseRefuse(),
                all.answerableHit(),
                all.answerableMiss());
    }

    /**
     * 在给定样本上挑阈值:约束是误答率不超过 {@link #MAX_FALSE_ANSWER_RATE},
     * 在此前提下让误拒率最小;并列时取更小的阈值(少拒答)。
     */
    private static Row pick(List<Obs> sample) {
        List<Double> candidates = new ArrayList<>();
        double max = 0;
        for (Obs o : sample) {
            candidates.add(o.top1());
            max = Math.max(max, o.top1());
        }
        // 保证总有一个候选能让误答率归零,否则约束可能无解
        candidates.add(max + 1.0);

        Row best = null;
        for (double threshold : candidates.stream().distinct().sorted().toList()) {
            Row row = stat(sample, threshold);
            if (row.falseAnswerRate() > MAX_FALSE_ANSWER_RATE) {
                continue;
            }
            if (best == null
                    || row.falseRefuseRate() < best.falseRefuseRate()
                    || (row.falseRefuseRate() == best.falseRefuseRate() && threshold < best.threshold())) {
                best = row;
            }
        }
        if (best == null) {
            // 理论上到不了这里(上面兜了一个必然满足约束的候选),留作防御
            best = stat(sample, max + 1.0);
        }
        return best;
    }

    private static Row stat(List<Obs> sample, double threshold) {
        int unanswerable = 0;
        int falseAnswer = 0;
        int answerableHit = 0;
        int falseRefuse = 0;
        int answerableMiss = 0;
        int avoidedWrong = 0;

        for (Obs o : sample) {
            if (!o.answerable()) {
                unanswerable++;
                if (o.top1() >= threshold) {
                    falseAnswer++;
                }
            } else if (o.top1Hit()) {
                answerableHit++;
                if (o.top1() < threshold) {
                    falseRefuse++;
                }
            } else {
                answerableMiss++;
                if (o.top1() < threshold) {
                    avoidedWrong++;
                }
            }
        }
        return new Row(threshold, falseAnswer, unanswerable,
                falseRefuse, answerableHit, avoidedWrong, answerableMiss);
    }

    /** 题号里偶数算调参集。确定性切分,保证每次跑都得到同一个阈值。 */
    private static boolean isTuneSet(String id) {
        int number = 0;
        for (char c : id.toCharArray()) {
            if (c >= '0' && c <= '9') {
                number = number * 10 + (c - '0');
            }
        }
        return number % 2 == 0;
    }

    private static boolean containsAll(String content, List<String> keyPhrases) {
        if (keyPhrases == null || keyPhrases.isEmpty()) {
            // 不可回答型没有关键短语,一律视为"没命中"
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

    private static String round(double value) {
        return String.format("%.4f", value);
    }

    public static String renderMarkdown(Result result) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## 拒答阈值校准\n\n");
        sb.append("目的:确定「精排分数低于多少就不必调用大模型」。\n\n");
        sb.append("两类题目分开统计, **绝不混算**:\n\n");
        sb.append("- **误答率** = 不可回答型题目里, 分数达到阈值会被回答的比例 —— 知识库里根本没有答案\n");
        sb.append("- **误拒率** = **本来答得对**(top-1 已命中)的可回答题里, 分数低于阈值会被拒答的比例\n\n");
        sb.append("检索本身就没找到答案的题不计入误拒 —— 那种情况下拒答反而是正确行为。\n");
        sb.append("这也决定了阈值的收益上限: 能避免的错答次数, 顶多是「top-1 未命中」的题目数(本项目 ")
                .append(result.answerableMissTotal()).append(" 题)。\n\n");

        sb.append("### 阈值权衡曲线(全量数据, 看趋势)\n\n");
        sb.append("| 阈值 | 误答率 | 误拒率 | 拦下的「本就答错」题 |\n");
        sb.append("|---|---|---|---|\n");
        for (Row row : result.curve()) {
            sb.append("| ").append(round(row.threshold()))
                    .append(" | ").append(percent(row.falseAnswerRate()))
                    .append(" (").append(row.falseAnswer()).append("/").append(row.unanswerable()).append(")")
                    .append(" | ").append(percent(row.falseRefuseRate()))
                    .append(" (").append(row.falseRefuse()).append("/").append(row.answerableHit()).append(")")
                    .append(" | ").append(row.avoidedWrong()).append("/").append(row.answerableMiss())
                    .append(" |\n");
        }

        sb.append("\n### 选定的阈值\n\n");
        sb.append("约束:误答率 ≤ 5%;在此前提下让误拒率最小。\n");
        sb.append("按题号奇偶切分 —— **偶数题挑阈值,奇数题只用于复核**。\n\n");
        sb.append("| | 不可回答题 | 本来答得对的可回答题 |\n");
        sb.append("|---|---|---|\n");
        sb.append("| 调参集(偶数题) | ").append(result.tuneUnanswerable())
                .append(" | ").append(result.tuneAnswerableHit()).append(" |\n");
        sb.append("| 验证集(奇数题) | ").append(result.holdoutUnanswerable())
                .append(" | ").append(result.holdoutAnswerableHit()).append(" |\n\n");
        sb.append("- **选定阈值:").append(round(result.chosenOnTune().threshold())).append("**\n");
        sb.append("- 在调参集上:误答率 ").append(percent(result.chosenOnTune().falseAnswerRate()))
                .append(" , 误拒率 ").append(percent(result.chosenOnTune().falseRefuseRate())).append("\n");
        sb.append("- **在验证集上(未参与挑阈值):误答率 ")
                .append(percent(result.holdoutFalseAnswerRate()))
                .append(" , 误拒率 ").append(percent(result.holdoutFalseRefuseRate())).append("**\n\n");
        sb.append("两边的差距就是过拟合程度的直接证据。\n\n");

        sb.append("### 必须说清的局限\n\n");
        sb.append("1. **不可回答题只有 20 道**,切一半之后每边 10 道 —— 一道题的变化就是 10 个百分点,\n");
        sb.append("   统计意义很弱。这个阈值只能作为方向性结论,不能直接当生产参数。\n");
        sb.append("2. 语料是合成的,(12 份文档 / 91 个分块),阈值随语料规模会漂移。\n");
        sb.append("3. 本校准只回答「阈值定多少」,**阈值尚未接入问答链路** ——\n");
        sb.append("   接入前应先确认代价是否可以接受(见上面曲线里的误拒率)。\n");
        return sb.toString();
    }
}
