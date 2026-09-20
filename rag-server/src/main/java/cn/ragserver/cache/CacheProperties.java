package cn.ragserver.cache;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 问答缓存配置,对应 application.yml 里的 rag.cache */
@ConfigurationProperties(prefix = "rag.cache")
@Getter
@Setter
public class CacheProperties {

    /** 总开关。测试「关掉缓存」的效果时可以临时设 false。 */
    private boolean enabled = true;

    /**
     * 缓存有效期。
     *
     * 知识库是低频变更的,1 小时足够。
     * 设短了命中率低,设长了浪费内存 —— 而且知识库真更新时,
     * 版本号机制会让旧缓存立刻失效,不必靠 TTL 兜底。
     */
    private Duration ttl = Duration.ofHours(1);

    /** 键前缀,便于在 Redis 里区分这是哪一类数据 */
    private String keyPrefix = "rag:qa";
}
