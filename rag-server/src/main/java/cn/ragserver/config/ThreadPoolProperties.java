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
 *
 * 【队列大小还会决定"最大线程数什么时候才生效"】
 *
 * 任务进池子的顺序是:
 *   ① 线程数 < core   -> 开新线程
 *   ② 队列没满        -> 进队列排队
 *   ③ 线程数 < max    -> 再开新线程
 *   ④ 执行拒绝策略
 *
 * 注意第 ②步在扩容之前 —— 所以**队列越长,max 越难被触发**。
 * 一开始 chat-stream 配的是 core=4 / queue=100:按理说 max=16,
 * 但要让线程数从 4 涨到 16,得先塞满整整 100 个任务才轮得到扩容。
 * 实测 8 个并发请求,日志里只有 4 个线程在跑 —— **实际并发上限就是 core**。
 *
 * 队列长度同时决定最长排队时间(≈ 队列长度 ÷ 吞吐),
 * 所以它同时也是"用户最多要等多久"的开关。
 */
@ConfigurationProperties(prefix = "rag.thread-pool")
@Getter
@Setter
public class ThreadPoolProperties {

    /** 流式问答:核心线程数 */
    private int chatStreamCoreSize = 8;

    /** 流式问答:最大线程数 */
    private int chatStreamMaxSize = 16;

    /** 流式问答:队列容量。超出后按拒绝策略处理 */
    private int chatStreamQueueCapacity = 20;

    /** 健康检查:核心线程数(和探测器数量对齐,当前 5 个) */
    private int healthCheckCoreSize = 5;

    /** 健康检查:最大线程数 */
    private int healthCheckMaxSize = 8;

    /** 健康检查:队列容量。健康检查调用频繁,队列要小 —— 排队久了结果就没意义了 */
    private int healthCheckQueueCapacity = 20;

    /** 空闲线程存活时间(秒) */
    private int keepAliveSeconds = 60;
}
