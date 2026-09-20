package cn.ragserver.health;

/**
 * 依赖探测器的统一接口。
 *
 * 每接入一个新的外部依赖,就新增一个实现类并标注 @Component,
 * 它会被自动收集进 SystemHealthService,不需要改动任何已有代码。
 *
 * 这也是 Spring 里最常用的一种扩展方式:面向接口 + 集合注入。
 * 第 7 项接入大模型 API、第 13 项接入 Elasticsearch 时,都只需要新增一个类。
 */
public interface DependencyChecker {

    /**
     * 展示给前端的依赖名称,例如 "PostgreSQL"。
     */
    String name();

    /**
     * 执行一次探测。正常返回即视为健康,抛出异常即视为不健康。
     */
    void check() throws Exception;
}
