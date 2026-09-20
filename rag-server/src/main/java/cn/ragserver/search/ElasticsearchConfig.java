package cn.ragserver.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch 客户端的装配。
 *
 * 分两层:
 *   RestClient            —— 底层 HTTP 客户端,负责连接池、重试、超时
 *   ElasticsearchClient   —— 官方的高层类型安全客户端,把请求/响应映射成 Java 对象
 *
 * 两层都注册成 Bean 的原因:RestClient 需要注册销毁回调来关闭连接池,
 * 否则应用反复重启会泄漏 HTTP 连接。
 */
@Configuration
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient(ElasticsearchProperties properties) {
        // HttpHost.create 能直接解析 "http://localhost:9200" 这种完整地址
        return RestClient.builder(HttpHost.create(properties.getUri())).build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        // JacksonJsonpMapper 让客户端用 Jackson 做 JSON 映射,和 Spring Boot 用的同一套
        return new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }
}
