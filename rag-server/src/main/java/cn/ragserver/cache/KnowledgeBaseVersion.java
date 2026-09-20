package cn.ragserver.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 知识库版本号。
 *
 * 【它解决什么问题】
 *
 * 缓存了答案之后,一旦知识库更新(文档被重新索引、被删除、被修改),
 * 缓存里的旧答案就变成了错误答案 —— 而且用户完全看不出来,
 * 因为答案读起来依然通顺。**这是缓存最危险的失败模式。**
 *
 * 【为什么用版本号,而不是删除缓存】
 *
 * 三种做法的对比:
 *
 *   全量清空:文档一变就 FLUSHDB。粗暴,所有缓存瞬间作废。
 *   逐个删除:遍历找出受影响的相关缓存删掉。复杂,而且很难算准影响范围 ——
 *            一道题的答案可能引用了被删文档里的分块,但缓存键和文档之间没有直接关联。
 *   版本号:  缓存键 = rag:qa:v{版本号}:{问题hash}。
 *            文档一变就把版本号加一,于是所有旧键自动不再被查找,
 *            靠 TTL 自然过期回收。
 *
 * 选第三种:不需要遍历删除,也不会有遗漏。
 * 代价是旧缓存会在 Redis 里多占一会儿内存,直到 TTL 到期。
 */
@Component
public class KnowledgeBaseVersion {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseVersion.class);

    private static final String VERSION_KEY = "rag:kb:version";

    private final StringRedisTemplate redisTemplate;

    public KnowledgeBaseVersion(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 当前版本号。Redis 不可用时返回 0,不抛异常。
     *
     * 缓存是「锦上添花」的功能,它坏掉不该让整个问答不可用。
     */
    public long current() {
        try {
            String value = redisTemplate.opsForValue().get(VERSION_KEY);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception ex) {
            log.warn("读取知识库版本号失败,缓存将被跳过:{}", ex.getMessage());
            return -1L;
        }
    }

    /**
     * 知识库发生变更时调用。
     *
     * 触发时机:文档索引完成、重新索引、删除。
     */
    public void bump() {
        try {
            Long version = redisTemplate.opsForValue().increment(VERSION_KEY);
            log.info("知识库已变更,版本号 -> {}", version);
        } catch (Exception ex) {
            log.warn("递增知识库版本号失败,旧缓存可能被继续使用:{}", ex.getMessage());
        }
    }
}
