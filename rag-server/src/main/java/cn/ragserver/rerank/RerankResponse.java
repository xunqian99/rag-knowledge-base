package cn.ragserver.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 重排序接口的返回体。
 *
 * 加 @JsonIgnoreProperties(ignoreUnknown = true) 是必须的:
 * 第三方接口随时可能加字段,不加这个的话,对方一改我们就反序列化失败。
 * **对于不由自己控制的接口,永远要允许未知字段。**
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RerankResponse(List<Result> results, Meta meta) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(int index, @JsonProperty("relevance_score") Double relevanceScore) {
    }

    /** 用量信息。第 23 项做成本统计时会用到。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(Tokens tokens) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tokens(@JsonProperty("input_tokens") Integer inputTokens) {
    }
}
