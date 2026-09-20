package cn.ragserver.search;

import cn.ragserver.common.BusinessException;
import cn.ragserver.retrieval.RetrievedChunk;
import cn.ragserver.retrieval.ChunkRetriever;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * BM25 关键词检索。
 */
@Service
@Order(2)
public class EsChunkSearcher implements ChunkRetriever {

    private static final Logger log = LoggerFactory.getLogger(EsChunkSearcher.class);

    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    public EsChunkSearcher(ElasticsearchClient client, ElasticsearchProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "bm25";
    }

    /**
     * 用 BM25 做关键词检索。
     *
     * 用 match 查询而不是 term:match 会先把关键词过一遍分析器(cjk),
     * 切成和索引里一致的二元组再做匹配 —— 两边分词方式必须一致,否则永远匹配不上。
     * term 查询不做分析,直接拿原始词去比对,中文场景基本用不了。
     */
    @Override
    public List<RetrievedChunk> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {
            SearchResponse<ChunkDocument> response = client.search(search -> search
                            .index(properties.getChunkIndex())
                            .query(q -> q.match(match -> match
                                    .field("content")
                                    .query(query)))
                            .size(topK),
                    ChunkDocument.class);

            List<RetrievedChunk> chunks = response.hits().hits().stream()
                    .map(this::toRetrievedChunk)
                    .toList();

            log.info("BM25 检索「{}」命中 {} 条,服务端耗时 {}ms", query, chunks.size(), response.took());
            return chunks;
        } catch (Exception ex) {
            throw new BusinessException("ES_SEARCH_FAILED",
                    "Elasticsearch 检索失败:" + ex.getMessage(), ex);
        }
    }

    private RetrievedChunk toRetrievedChunk(Hit<ChunkDocument> hit) {
        // 这里的 score 是 BM25 得分,不是相似度 ——
        // 它没有上界,也不是 0~1 之间的值。第 15 项混用两种检索结果时,
        // 只能比排名,不能直接比分数。
        double score = hit.score() == null ? 0.0 : hit.score();
        ChunkDocument document = hit.source();
        if (document == null) {
            return new RetrievedChunk(0L, 0L, "", 0, "", score);
        }
        return new RetrievedChunk(
                document.chunkId(),
                document.documentId(),
                document.fileName(),
                document.chunkIndex(),
                document.content(),
                score);
    }
}
