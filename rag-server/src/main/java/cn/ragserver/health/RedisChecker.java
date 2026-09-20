package cn.ragserver.health;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 探测器。
 *
 * 用 ping 命令而不是读写一个 key:探测不该改动业务数据,
 * 而且 ping 是 Redis 里开销最小的命令。
 */
@Component
public class RedisChecker implements DependencyChecker {

    private final StringRedisTemplate redisTemplate;

    public RedisChecker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String name() {
        return "Redis";
    }

    @Override
    public void check() {
        // execute 需要一个明确的 RedisCallback,StringRedisTemplate 上有多个重载,
        // 这里显式声明类型避免编译器无法推断。
        String pong = redisTemplate.execute((RedisCallback<String>) RedisConnection::ping);
        if (!"PONG".equalsIgnoreCase(pong)) {
            throw new IllegalStateException("Redis 未返回 PONG,实际返回:" + pong);
        }
    }
}
