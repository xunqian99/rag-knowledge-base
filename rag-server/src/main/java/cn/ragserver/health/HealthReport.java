package cn.ragserver.health;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;

/**
 * 健康检查的返回体。
 *
 * 用 record 而不是普通类:这是只读的数据载体,
 * record 自动生成构造器、getter、equals、hashCode,少写几十行样板代码(JDK 16+ 特性)。
 */
public record HealthReport(
        String status,
        Instant checkedAt,
        long costMs,
        List<DependencyStatus> dependencies) {

    /** 单个依赖的探测结果 */
    public record DependencyStatus(
            String name,
            String status,
            long costMs,
            String error) {
    }

    /**
     * 供 Controller 判断 HTTP 状态码用,不参与 JSON 序列化。
     *
     * 加 @JsonIgnore 的原因:Jackson 会把 isXxx() 形式的方法当成属性 getter,
     * 自动序列化成一个名为 "up" 的字段。而 status 里已经表达了同样的信息,
     * 多出来这个字段会让接口契约变得冗余。
     */
    @JsonIgnore
    public boolean isUp() {
        return "UP".equals(status);
    }
}
