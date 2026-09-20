package cn.ragserver.embedding;

import cn.ragserver.common.BusinessException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 把文本批量转成向量。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel embeddingModel;
    private final EmbeddingProperties properties;

    public EmbeddingService(EmbeddingModel embeddingModel, EmbeddingProperties properties) {
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    /**
     * 批量生成向量,返回顺序与传入的文本一一对应。
     *
     * 分批发送的原因:接口对单次请求的文本条数和总 token 数都有上限,
     * 一次全发出去大概率会被拒。
     */
    public List<float[]> embedAll(List<String> texts) {
        ensureConfigured();

        if (texts.isEmpty()) {
            return List.of();
        }

        int batchSize = Math.max(1, properties.getBatchSize());
        List<float[]> vectors = new ArrayList<>(texts.size());
        long start = System.nanoTime();

        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(from + batchSize, texts.size());

            List<TextSegment> batch = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                batch.add(TextSegment.from(texts.get(i)));
            }

            Response<List<Embedding>> response = embeddingModel.embedAll(batch);
            for (Embedding embedding : response.content()) {
                vectors.add(embedding.vector());
            }
        }

        // 数量必须对得上。对不上说明接口返回异常,
        // 如果放任不管,后面按下标取向量会全体错位 —— 检索结果会变得毫无意义。
        if (vectors.size() != texts.size()) {
            throw new BusinessException("EMBEDDING_RESULT_MISMATCH",
                    "向量接口返回数量不符,期望 " + texts.size() + " 条,实际 " + vectors.size() + " 条");
        }

        checkDimension(vectors.get(0).length);

        log.info("向量化完成:{} 条文本,{} 个批次,耗时 {}ms",
                texts.size(), (texts.size() + batchSize - 1) / batchSize,
                (System.nanoTime() - start) / 1_000_000);

        return vectors;
    }

    /**
     * 校验实际维度是否与配置一致。
     *
     * 这一步非常关键:数据库里的列是 vector(1024),如果模型实际输出 384 维,
     * 写入时会直接报错,但错误信息很可能让人摸不着头脑。
     * 在这里主动比对,并给出「该改哪里」的明确提示,省掉大量排查时间。
     */
    private void checkDimension(int actual) {
        if (actual == properties.getDimension()) {
            return;
        }
        throw new BusinessException("EMBEDDING_DIMENSION_MISMATCH",
                ("模型 %s 实际输出 %d 维,但配置写的是 %d 维。"
                        + "请把 rag.embedding.dimension 改成 %d,"
                        + "并新增一个 Flyway 迁移把 document_chunk.embedding 改成 vector(%d)")
                        .formatted(properties.getModel(), actual, properties.getDimension(), actual, actual));
    }

    private void ensureConfigured() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new BusinessException("EMBEDDING_NOT_CONFIGURED",
                    "未配置向量模型密钥。请在 application-local.yml 里填写 rag.embedding.api-key,"
                            + "并以 local 配置启动应用。");
        }
    }
}
