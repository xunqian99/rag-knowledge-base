package cn.ragserver.rerank;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 重排序接口的请求体。
 *
 * 接口用的是 snake_case(top_n、return_documents),而 Java 侧是 camelCase,
 * 所以要用 @JsonProperty 显式映射。
 *
 * 为什么不全局配置 spring.jackson.property-naming-strategy=SNAKE_CASE:
 * 那会把我们自己的接口返回也一起改成 snake_case。
 * **第三方接口的命名风格不该污染自己的接口契约。**
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RerankRequest(
        String model,
        String query,
        List<String> documents,
        @JsonProperty("top_n") Integer topN,
        @JsonProperty("return_documents") Boolean returnDocuments) {
}
