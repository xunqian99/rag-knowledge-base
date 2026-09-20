package cn.ragserver.health;

import cn.ragserver.config.ThreadPoolConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * 依赖自检服务。
 */
@Service
public class SystemHealthService {

    private static final Logger log = LoggerFactory.getLogger(SystemHealthService.class);
    private final List<DependencyChecker> checkers;
    private final Executor executor;
    private final long timeoutMs;

    /**
     * 注意构造器参数上的 @Value:这是「构造器注入 + 外部配置」的组合写法。
     * 冒号后面的 2000 是默认值,配置里没写就用它。
     */
    public SystemHealthService(List<DependencyChecker> checkers,
                               @Value("${rag.health.timeout-ms:2000}") long timeoutMs,
                               @Qualifier(ThreadPoolConfig.HEALTH_CHECK_EXECUTOR) Executor executor) {
        this.checkers = checkers;
        this.timeoutMs = timeoutMs;
        // 为什么要有独立线程池:健康检查不能被慢依赖拖死(每个探测加超时,超时就判 DOWN),
        // 也不能借用 Tomcat 的业务线程 —— 否则依赖一慢,大量业务线程会被占住,服务雪崩。
        // 具体参数见 rag.thread-pool.health-check。
        this.executor = executor;

        log.info("健康检查注册了 {} 个依赖探测器:{}", checkers.size(),
                checkers.stream().map(DependencyChecker::name).toList());
    }

    public HealthReport check() {
        long start = System.nanoTime();

        // 并行探测所有依赖,总耗时约等于最慢的那一个,而不是各个相加。
        List<CompletableFuture<HealthReport.DependencyStatus>> futures = checkers.stream()
                .map(checker -> CompletableFuture
                        .supplyAsync(() -> runOne(checker), executor)
                        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .exceptionally(ex -> {
                            log.warn("依赖探测超时:{} 超过 {}ms", checker.name(), timeoutMs);
                            return new HealthReport.DependencyStatus(
                                    checker.name(), "DOWN", timeoutMs,
                                    "探测超时(超过 " + timeoutMs + "ms 未返回)");
                        }))
                .toList();

        // 走到这里时上面所有 future 都已经有了结果(成功或超时),
        // join 不会阻塞,只是把结果取出来。
        List<HealthReport.DependencyStatus> dependencies = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        boolean allUp = dependencies.stream().allMatch(d -> "UP".equals(d.status()));
        return new HealthReport(allUp ? "UP" : "DOWN", Instant.now(),
                (System.nanoTime() - start) / 1_000_000, dependencies);
    }

    private HealthReport.DependencyStatus runOne(DependencyChecker checker) {
        long start = System.nanoTime();
        try {
            checker.check();
            return new HealthReport.DependencyStatus(checker.name(), "UP", elapsedMs(start), null);
        } catch (Exception ex) {
            // 这里把异常吃掉、转成 DOWN 状态返回,而不是往外抛。
            // 健康检查接口的职责是「报告」而不是「失败」,
            // 它自己必须始终能给出结构化结果。
            log.warn("依赖探测失败:{}", checker.name(), ex);
            return new HealthReport.DependencyStatus(checker.name(), "DOWN", elapsedMs(start), rootMessage(ex));
        }
    }

    private static long elapsedMs(long startNanoTime) {
        return (System.nanoTime() - startNanoTime) / 1_000_000;
    }

    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }

}
