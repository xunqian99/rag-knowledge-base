package cn.ragserver.search;

import cn.ragserver.health.DependencyChecker;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 的健康检查。
 *
 * 因为项目用的是官方 Java 客户端而不是 Spring Data Elasticsearch,
 * Actuator 自带的 Elasticsearch 健康指示器不会生效,所以要自己写一个 ——
 * 这正是第 4 项说的「等接入 Actuator 不认识的依赖时再写」的那个场景。
 */
@Component
public class ElasticsearchChecker implements DependencyChecker {

    private final ElasticsearchClient client;

    public ElasticsearchChecker(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "Elasticsearch";
    }

    @Override
    public void check() throws Exception {
        // 查集群健康状态,是最轻量又能证明「服务真的活着」的调用
        client.cluster().health();
    }
}
