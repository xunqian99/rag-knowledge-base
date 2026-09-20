package cn.ragserver.rerank;

import cn.ragserver.health.DependencyChecker;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 重排序依赖的健康检查。
 *
 * 和向量模型那边一样:只检查密钥配没配,不真的调接口。
 *
 * 理由仍然是成本 —— 健康检查会被监控系统每隔几秒调用一次,
 * 每次都真的去调一次重排序接口会持续消耗额度。
 * 而且重排序要传几十份文档,单次开销比普通接口大得多。
 */
@Component
public class RerankConfigChecker implements DependencyChecker {

    private final RerankProperties properties;

    public RerankConfigChecker(RerankProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "重排序(" + properties.getModel() + ")";
    }

    @Override
    public void check() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException("未配置 rag.rerank.api-key");
        }
    }
}
