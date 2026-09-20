package cn.ragserver.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 线程池配置,对应 application.yml 里的 rag.thread-pool。
 *
 * 【为什么要显式配队列容量】
 *
 * 不设上限的队列是线上事故的经典成因:高峰期请求不断排进队列,
 * 队列越排越长、内存一直涨,最后 OOM —— 而且**在崩溃之前系统看起来一切正常**,
 * 只是越来越慢。等发现的时候已经晚了。
 *
 * 设了容量上限之后,超出的请求会被拒绝策略立刻处理掉(本项目选择直接拒绝),
 * 调用方马上收到「服务繁忙」而不是无限等待。
 *
 * **快速失败比慢慢崩溃好。**
 */
@ConfigurationProperties(prefix = "rag.thread-pool")
@Getter
@Setter
public class ThreadPoolProperties {

    /** 流式问答:核心线程数 */
    private int chatStreamCoreSize = 4;

    /** 流式问答:最大线程数 */
    private int chatStreamMaxSize = 16;

    /** 流式问答:队列容量。超出后按拒绝策略处理 */
    private int chatStreamQueueCapacity = 100;

    /** 健康检查:核心线程数(和探测器数量对齐即可) */
    private int healthCheckCoreSize = 4;

    /** 健康检查:最大线程数 */
    private int healthCheckMaxSize = 8;

    /** 健康检查:队列容量。健康检查调用频繁,队列要小 —— 排队久了结果就没意义了 */
    private int healthCheckQueueCapacity = 20;

    /** 空闲线程存活时间(秒) */
    private int keepAliveSeconds = 60;
}
