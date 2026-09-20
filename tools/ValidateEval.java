import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评测集自动校验。
 *
 * 大模型生成的问题和答案不能全信。这个程序做三件事:
 *   1. 解析检查:每一行是不是合法 JSON、id 有没有重复
 *   2. 可答性检查:可回答的题目,它的 keyPhrases 必须真的能在库里的分块中找到
 *   3. 松紧检查:一条题目的 keyPhrases 命中了多少个分块 ——
 *      命中 0 个说明题目本身有问题(标注错了);
 *      命中太多说明 keyPhrases 不够有区分度,该题会失去评测意义
 */
public class ValidateEval {

    record Question(String id, String type, String question, String answer,
                    String source, List<String> keyPhrases, boolean answerable) {
    }

    record Chunk(long id, long documentId, String fileName, String content) {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<Question> questions = new ArrayList<>();
        List<String> badLines = new ArrayList<>();

        List<String> lines = Files.readAllLines(Paths.get("C:/Users/xunqian/Desktop/1/eval/questions.jsonl"));
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            try {
                questions.add(mapper.readValue(line, Question.class));
            } catch (Exception ex) {
                badLines.add("第 " + lineNo + " 行解析失败: " + ex.getMessage());
            }
        }

        System.out.println("== 1. 解析检查 ==");
        System.out.println("  总条数: " + questions.size());
        if (!badLines.isEmpty()) {
            badLines.forEach(s -> System.out.println("  [问题] " + s));
        }

        Set<String> ids = new HashSet<>();
        Set<String> texts = new HashSet<>();
        int dupId = 0;
        int dupText = 0;
        for (Question q : questions) {
            if (!ids.add(q.id())) {
                dupId++;
                System.out.println("  [重复 id] " + q.id());
            }
            if (!texts.add(q.question())) {
                dupText++;
                System.out.println("  [重复问题] " + q.question());
            }
        }
        System.out.printf("  重复 id: %d,重复问题: %d%n", dupId, dupText);

        Map<String, Integer> byType = new LinkedHashMap<>();
        for (Question q : questions) {
            byType.merge(q.type(), 1, Integer::sum);
        }
        System.out.println("  按类型: " + byType);

        List<Chunk> chunks = new ArrayList<>();
        String url = "jdbc:postgresql://localhost:5432/rag?user=rag&password=rag123456";
        try (Connection c = DriverManager.getConnection(url);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "select c.id, c.document_id, d.file_name, c.content " +
                     "from document_chunk c join document d on d.id = c.document_id " +
                     "where d.status = 'INDEXED'")) {
            while (rs.next()) {
                chunks.add(new Chunk(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4)));
            }
        }
        System.out.println("  库中分块数: " + chunks.size());

        System.out.println("\n== 2. 可答性检查(keyPhrases 必须能在某个分块中找到)==");
        List<String> missing = new ArrayList<>();
        List<String> tooLoose = new ArrayList<>();
        Map<String, Integer> hitsByType = new HashMap<>();

        for (Question q : questions) {
            if (!q.answerable()) {
                continue;
            }
            List<Chunk> matched = new ArrayList<>();
            for (Chunk chunk : chunks) {
                boolean all = true;
                for (String phrase : q.keyPhrases()) {
                    if (!chunk.content().contains(phrase)) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    matched.add(chunk);
                }
            }
            hitsByType.merge(q.type(), matched.size(), Integer::sum);

            if (matched.isEmpty()) {
                missing.add(q.id() + "  " + q.question() + "  关键短语=" + q.keyPhrases());
            } else if (matched.size() > 6) {
                tooLoose.add(q.id() + "  命中 " + matched.size() + " 个块  " + q.question());
            }
        }

        System.out.println("  关键短语找不到任何分块的题目数: " + missing.size());
        missing.forEach(s -> System.out.println("    [需修] " + s));
        System.out.println("  命中超过 6 个块的题目数(keyPhrases 区分度低): " + tooLoose.size());
        tooLoose.forEach(s -> System.out.println("    [偏松] " + s));

        System.out.println("\n== 3. 汇总 ==");
        System.out.println("  平均每个可回答问题命中的分块数(按类型): " + hitsByType);
    }
}
