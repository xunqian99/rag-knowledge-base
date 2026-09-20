package cn.ragserver.common;

/**
 * 业务异常。
 *
 * 它和普通 Exception 的区别在于:这是「意料之中的失败」,
 * 比如文件为空、类型不对、超出大小限制。
 * 这类异常要把原因原样告诉用户,而不是被统一吞成一句「服务内部错误」。
 *
 * 所以它带一个 code 字段,由 GlobalExceptionHandler 翻译成结构化错误码返回。
 */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
