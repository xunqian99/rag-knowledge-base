package cn.ragserver.search;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Elasticsearch 配置,对应 application.yml 里的 rag.elasticsearch */
@ConfigurationProperties(prefix = "rag.elasticsearch")
@Getter
@Setter
public class ElasticsearchProperties {

    private String uri = "http://localhost:9200";

    /**
     * 分块索引名。
     *
     * 特意带上 v1 后缀:以后要改 mapping(比如换分词器)时,
     * 可以新建 rag-chunk-v2 把所有数据重建进去,验证没问题再切过来,
     * 而不是在原索引上直接改 —— 后者需要停服重建,风险大得多。
     */
    private String chunkIndex = "rag-chunk-v1";

    /** 批量写入的最大条数 */
    private int bulkSize = 200;

    private Duration timeout = Duration.ofSeconds(10);
}
