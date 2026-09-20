package cn.ragserver.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 面向业务方的健康检查接口。
 *
 * 它和 Actuator 的 /actuator/health 是两层,分工不同:
 *
 *   /actuator/health  给运维和容器编排用,格式由 Spring 决定,
 *                     会输出数据库地址、驱动版本等内部信息,不适合直接对公网开放。
 *
 *   /api/system/health 给前端用,字段是自己定义的,只暴露必要信息。
 */
@RestController
@RequestMapping("/api/system")
public class HealthController {

    private final SystemHealthService healthService;

    public HealthController(SystemHealthService healthService) {
        this.healthService = healthService;
    }

    /**
     * 全部依赖健康返回 200,否则返回 503。
     *
     * 为什么不是「永远返回 200,把状态写在 body 里」:
     * 监控系统、负载均衡、K8s 探针都是看 HTTP 状态码决定要不要告警、
     * 要不要把实例摘掉的。状态码不对,它们就看不见问题。
     * 返回体照常给出,方便排错。
     */
    @GetMapping("/health")
    public ResponseEntity<HealthReport> health() {
        HealthReport report = healthService.check();
        return report.isUp()
                ? ResponseEntity.ok(report)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(report);
    }
}
