package cn.ragserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池的统一管理。
 *
 * 之前有两处自己 new 线程池(健康检查、流式生成),参数硬编码在里面:
 * 改不了、没有队列上限、没有拒绝策略、也看不到运行状态。
 *
 * 收拢到这里之后有三个好处:
 *   1. 参数集中在配置文件里,不同环境可以不同
 *   2. 队列有上限,超载时快速失败而不是无限堆积
 *   3. Spring 管理生命周期,应用关闭时自动优雅停止
 */
@Configuration
public class ThreadPoolConfig {

    public static final String CHAT_STREAM_EXECUTOR = "chatStreamExecutor";
    public static final String HEALTH_CHECK_EXECUTOR = "healthCheckExecutor";

    @Bean(name = CHAT_STREAM_EXECUTOR)
    public ThreadPoolTaskExecutor chatStreamExecutor(ThreadPoolProperties properties) {
        return build("chat-stream-", properties.getChatStreamCoreSize(),
                properties.getChatStreamMaxSize(), properties.getChatStreamQueueCapacity(),
                properties.getKeepAliveSeconds());
    }

    @Bean(name = HEALTH_CHECK_EXECUTOR)
    public ThreadPoolTaskExecutor healthCheckExecutor(ThreadPoolProperties properties) {
        return build("health-check-", properties.getHealthCheckCoreSize(),
                properties.getHealthCheckMaxSize(), properties.getHealthCheckQueueCapacity(),
                properties.getKeepAliveSeconds());
    }

    /**
     * 拒绝策略选 AbortPolicy(直接抛异常),而不是 CallerRunsPolicy。
     *
     * CallerRuns 看起来「更友好」—— 它不丢任务,让提交任务的线程自己跑。
     * 但对流式问答来说那是灾难:它会把生成任务压回 Tomcat 的工作线程,
     * 于是本该被保护的 Web 线程池反而被拖垮,**保护等于失效**。
     *
     * 直接拒绝则让调用方立刻收到明确信号,由上层翻译成 503 返回给用户。
     */
    private static ThreadPoolTaskExecutor build(String threadNamePrefix, int coreSize,
                                                int maxSize, int queueCapacity, int keepAliveSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setDaemon(true);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
