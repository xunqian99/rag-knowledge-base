package cn.ragserver.cache;

import cn.ragserver.chat.AnswerResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 问答缓存。
 *
 * 【缓存的失败模式比它带来的收益更需要警惕】
 *
 * 缓存命中时用户拿到的是一个"上次的答案"。如果这个答案是错的 ——
 * 比如知识库已经更新、或者两个问题被误判为同一个 ——
 * 用户看到的内容依然通顺、依然言之凿凿,**完全没有办法察觉**。
 *
 * 所以本项目的两个设计都偏向保守:
 *   1. 只做归一化后的精确匹配,不做语义匹配(避免误命中)
 *   2. 缓存键里带知识库版本号(知识库一变,旧缓存全部自动失效)
 */
@Service
public class QaCacheService {

    private static final Logger log = LoggerFactory.getLogger(QaCacheService.class);

    /**
     * 归一化时要剔除的噪声:空白字符 + 中英文标点。
     *
     * 剔除之后,"出差住宿能报多少?"和"出差住宿能报多少"会归一到同一个键。
     * 这是在**零误命中风险**的前提下能拿到的最大命中率提升 ——
     * 因为只做字符级别的规整,不做任何语义判断。
     */
    private static final Pattern NOISE = Pattern.compile(
            "[\\s\\p{Punct}\\u3002\\uFF0C\\uFF1F\\uFF01\\uFF1B\\uFF1A\\u3001"
                    + "\\uFF08\\uFF09\\u3010\\u3011\\u201C\\u201D\\u2018\\u2019]");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CacheProperties properties;
    private final KnowledgeBaseVersion version;

    public QaCacheService(StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper,
                          CacheProperties properties,
                          KnowledgeBaseVersion version) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.version = version;
    }

    public Optional<CachedAnswer> get(String question) {
        String key = buildKey(question);
        if (key == null) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, CachedAnswer.class));
        } catch (Exception ex) {
            // 缓存读失败一律按"未命中"处理,让请求走正常链路。
            // 绝不能因为缓存坏了就让整个问答不可用。
            log.warn("读取缓存失败,按未命中处理:{}", ex.getMessage());
            return Optional.empty();
        }
    }

    public void put(String question, String answer, List<AnswerResponse.Citation> citations) {
        String key = buildKey(question);
        if (key == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(new CachedAnswer(answer, citations));
            redisTemplate.opsForValue().set(key, json, properties.getTtl());
        } catch (Exception ex) {
            log.warn("写入缓存失败:{}", ex.getMessage());
        }
    }

    /**
     * 构造缓存键。
     *
     * 返回 null 表示"这次不要用缓存",调用方直接跳过 ——
     * 可能因为缓存被关了,也可能因为 Redis 当前不可用(版本号读不到)。
     */
    private String buildKey(String question) {
        if (!properties.isEnabled()) {
            return null;
        }
        long kbVersion = version.current();
        if (kbVersion < 0) {
            return null;
        }
        // 用 MD5 只是为了让键定长,避免超长问题时键也跟着变得很长。
        // 这里不涉及任何安全场景,用 MD5 和用 SHA 在效果上没有区别。
        String hash = DigestUtils.md5DigestAsHex(
                normalize(question).getBytes(StandardCharsets.UTF_8));
        return properties.getKeyPrefix() + ":v" + kbVersion + ":" + hash;
    }

    static String normalize(String question) {
        return NOISE.matcher(question).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
