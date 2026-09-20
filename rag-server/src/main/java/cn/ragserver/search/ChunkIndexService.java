package cn.ragserver.search;

import cn.ragserver.common.BusinessException;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分块索引的维护:建索引、写入、按文档删除。
 */
@Service
public class ChunkIndexService {

    private static final Logger log = LoggerFactory.getLogger(ChunkIndexService.class);

    /**
     * 索引的 mapping 定义。
     *
     * content 用 cjk 分析器,这是整个索引里最关键的一行配置。
     *
     * ES 默认的 standard 分析器会把中文按单字切开(星/海/科/技),
     * 几乎每份中文文档都含「的」「管」「理」这类字,BM25 的逆文档频率失去意义,
     * 打分退化成近似随机。
     *
     * cjk 按二元组切分(星海/海科/科技/差旅/管理),是 ES 内置的,
     * 不用装任何插件就能让中文检索正常工作。实测对比见决策记录 013。
     *
     * fileName 和 section 用 keyword 而不是 text:keyword 不分词,
     * 适合精确匹配和聚合。如果也用 text,「员工手册」会被切成「员工」「工手」「手册」,
     * 按文件名过滤就不可靠了。
     */
    private static final String INDEX_MAPPING = """
            {
              "mappings": {
                "properties": {
                  "chunkId":    { "type": "long" },
                  "documentId": { "type": "long" },
                  "fileName":   { "type": "keyword" },
                  "chunkIndex": { "type": "integer" },
                  "section":    { "type": "keyword" },
                  "content":    { "type": "text", "analyzer": "cjk" }
                }
              }
            }
            """;

    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    /**
     * 索引是否已就绪。
     *
     * 做成懒加载而不是启动时创建,理由和向量模型那边一样:
     * Elasticsearch 没起来不应该导致整个应用启动失败,
     * 只应让依赖它的功能不可用,并在健康检查里体现出来。
     */
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    public ChunkIndexService(ElasticsearchClient client, ElasticsearchProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 批量写入分块。
     *
     * 用 chunkId 作为文档 _id,重复写入是幂等的(覆盖而非追加),
     * 这一点在重新索引时很重要。
     *
     * 这里刻意没有加 refresh=true:ES 默认每秒自动刷新一次,
     * 也就是说刚写完的数据最多 1 秒后才对检索可见。
     * 对「上传后马上提问」这个场景,1 秒延迟可以接受;
     * 而每次写入都强制 refresh 会不断重建 Lucene 段,写入吞吐会明显下降。
     */
    public void indexChunks(List<ChunkDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        ensureIndex();

        try {
            BulkRequest.Builder builder = new BulkRequest.Builder();
            for (ChunkDocument document : documents) {
                builder.operations(operation -> operation.index(index -> index
                        .index(properties.getChunkIndex())
                        .id(String.valueOf(document.chunkId()))
                        .document(document)));
            }

            BulkResponse response = client.bulk(builder.build());
            if (response.errors()) {
                long failed = response.items().stream().filter(item -> item.error() != null).count();
                throw new BusinessException("ES_INDEX_FAILED",
                        "批量写入 Elasticsearch 有 " + failed + " 条失败");
            }
            log.info("已写入 Elasticsearch:{} 条分块", documents.size());
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("ES_INDEX_FAILED",
                    "写入 Elasticsearch 失败:" + ex.getMessage(), ex);
        }
    }

    /**
     * 按文档删除它在索引里的全部分块。
     *
     * refresh(true) 让删除立刻对后续检索可见。生产环境通常不这么做,
     * 因为每次 refresh 都要重建 Lucene 段,有开销。
     * 但文档删除是低频操作,这里立刻生效比省这点开销重要。
     */
    public void deleteByDocumentId(long documentId) {
        ensureIndex();
        try {
            client.deleteByQuery(query -> query
                    .index(properties.getChunkIndex())
                    .query(q -> q.term(term -> term.field("documentId").value(documentId)))
                    .refresh(true));
            log.info("已从 Elasticsearch 删除文档 {} 的全部分块", documentId);
        } catch (Exception ex) {
            throw new BusinessException("ES_DELETE_FAILED",
                    "从 Elasticsearch 删除失败:" + ex.getMessage(), ex);
        }
    }

    /**
     * 确保索引存在。双重检查加锁:常规路径只读一次标志,不进入同步块。
     */
    private void ensureIndex() {
        if (indexReady.get()) {
            return;
        }
        synchronized (this) {
            if (indexReady.get()) {
                return;
            }
            String indexName = properties.getChunkIndex();
            try {
                boolean exists = client.indices().exists(e -> e.index(indexName)).value();
                if (!exists) {
                    client.indices().create(c -> c.index(indexName).withJson(new StringReader(INDEX_MAPPING)));
                    log.info("已创建 Elasticsearch 索引:{}", indexName);

                    // 新建索引后要等集群状态传播完成,否则紧接着的写入可能报 index_not_found。
                    // 单节点、副本数为 0 时通常瞬间就绪,但显式等待比碰运气可靠。
                    client.cluster().health(h -> h.index(indexName)
                            .waitForStatus(HealthStatus.Yellow)
                            .timeout(t -> t.time("10s")));
                }
                indexReady.set(true);
            } catch (Exception ex) {
                throw new BusinessException("ES_INDEX_UNAVAILABLE",
                        "Elasticsearch 不可用或索引创建失败:" + ex.getMessage(), ex);
            }
        }
    }
}
