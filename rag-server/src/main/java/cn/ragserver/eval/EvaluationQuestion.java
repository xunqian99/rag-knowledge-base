package cn.ragserver.eval;

import java.util.List;

/**
 * 评测集里的一道题。
 *
 * 字段与 eval/questions.jsonl 一一对应。
 *
 * answerable 为 false 的题目(不可回答型)不参与 Hit Rate / MRR 的计算 ——
 * 它们没有关键短语,考察的是"系统会不会承认自己不知道",那是另一类指标。
 */
public record EvaluationQuestion(
        String id,
        String type,
        String question,
        String answer,
        String source,
        List<String> keyPhrases,
        boolean answerable) {
}
