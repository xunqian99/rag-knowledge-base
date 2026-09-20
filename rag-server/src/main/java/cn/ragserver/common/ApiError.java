package cn.ragserver.common;

import java.time.Instant;

/**
 * 统一的错误返回体。
 *
 * 三个字段的分工:
 *   code      —— 给程序判断用的稳定机器码,前端按它做分支
 *   message   —— 给人看的说明,可以直接展示
 *   timestamp —— 出问题的时间,便于对着日志排查
 */
public record ApiError(String code, String message, Instant timestamp) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now());
    }
}
