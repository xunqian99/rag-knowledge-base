package cn.ragserver.eval;

import cn.ragserver.RagServerApplication;
import cn.ragserver.document.Document;
import cn.ragserver.document.DocumentService;
import cn.ragserver.document.DocumentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 评测的命令行入口。
 *
 * 【怎么运行】在 IDEA 里直接右键 Run 这个类即可(需要 spring.profiles.active=local,
 * 代码里已经默认带上)。
 *
 * 【为什么不用 Web 接口触发】
 *
 * 跑完四种配置大约 5 分钟。放进 HTTP 请求里必然超时,
 * 而且评测是一次性的批处理任务,本来就不该占用常驻的服务端口。
 *
 * 这里用 WebApplicationType.NONE 启动 Spring 容器 ——
 * **不启动 Tomcat,复用完整的依赖注入,跑完自动退出**。
 * 这样既拿到了 Spring 容器里现成的检索组件,又不用背着 Web 服务器的包袱。
 *
 * 【可用的命令行参数】
 *   --eval.reindex=true            评测前先按当前分块参数重新索引全部文档
 *   --eval.mode=HYBRID_RERANK      只评测指定的一种配置(默认 ALL,即全部四种)
 *   --rag.chunking.max-size=250    临时覆盖任意 Spring 配置项
 *
 * 后两个参数配合起来,一条命令就能跑完一组调参实验。
 */
public class EvaluationMain {

    private static final Logger log = LoggerFactory.getLogger(EvaluationMain.class);

    public static void main(String[] args) throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(RagServerApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("local")
                .run(args)) {

            Environment env = context.getEnvironment();

            if (Boolean.TRUE.equals(env.getProperty("eval.reindex", Boolean.class, Boolean.FALSE))) {
                reindexAll(context);
            }

            EvaluationRunner runner = context.getBean(EvaluationRunner.class);

            Path questionFile = runner.resolveQuestionFile();
            List<EvaluationQuestion> questions = runner.loadQuestions();

            String modeName = env.getProperty("eval.mode", "ALL");
            List<RetrievalMode> modes = "ALL".equalsIgnoreCase(modeName)
                    ? List.of(RetrievalMode.values())
                    : List.of(RetrievalMode.valueOf(modeName.trim().toUpperCase()));

            Map<RetrievalMode, EvaluationMetrics> results = runner.runAll(questions, modes);

            String report = EvaluationRunner.renderReport(results);
            System.out.println();
            System.out.println(report);

            // 报告写到评测集旁边,而不是当前工作目录 ——
            // 从项目根目录跑和从 rag-server 目录跑,结果应该落在同一个地方。
            Path reportPath = questionFile.toAbsolutePath().getParent().resolve("report.md");
            Files.writeString(reportPath, report);
            log.info("报告已写入 {}", reportPath);
        }
    }

    /**
     * 按当前的分块参数把所有已索引文档重跑一遍。
     *
     * 调参时改的是 rag.chunking.*,那是切分阶段读的配置 ——
     * 所以改完之后必须重新切分,否则评测的还是旧分块。
     */
    private static void reindexAll(ConfigurableApplicationContext context) {
        DocumentService documentService = context.getBean(DocumentService.class);
        int count = 0;
        int page = 0;
        while (true) {
            Page<Document> documents = documentService.list(page, 100);
            for (Document document : documents.getContent()) {
                if (document.getStatus() == DocumentStatus.INDEXED) {
                    documentService.reindex(document.getId());
                    count++;
                }
            }
            if (!documents.hasNext()) {
                break;
            }
            page++;
        }
        log.info("已按当前分块参数重新索引 {} 份文档", count);
    }
}
