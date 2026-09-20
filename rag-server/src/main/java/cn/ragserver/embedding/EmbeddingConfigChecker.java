package cn.ragserver.embedding;

import cn.ragserver.health.DependencyChecker;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 千帆 API Key 的健康检查。
 *
 * 向量化和对话用的是同一个 Key,所以这一个检查项同时覆盖两者,
 * 不再单独为对话模型加一个 —— 那样只是重复。
 *
 * 【这里只检查「密钥配没配」,不真的去调接口】
 *
 * 这是个刻意的取舍。健康检查会被监控系统、负载均衡每隔几秒调用一次,
 * 如果每次都真的去调一次向量接口:
 *   1. 会持续消耗 API 额度(按调用量计费,是真金白银)
 *   2. 外部接口慢,会把健康检查的响应时间拖长,监控反而失真
 *   3. 网络抖动时健康检查频繁误报,导致无意义的告警
 *
 * 所以配置类问题在健康检查里暴露,连通性问题留给实际的业务调用去发现。
 * 如果确实需要探测连通性,正确做法是单独做一个低频的定时探测,
 * 而不是塞进健康检查接口。
 */
@Component
public class EmbeddingConfigChecker implements DependencyChecker {

    private final EmbeddingProperties properties;

    public EmbeddingConfigChecker(EmbeddingProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "千帆 API Key";
    }

    @Override
    public void check() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException("未配置 rag.embedding.api-key");
        }
    }
}
