package cn.ragserver.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.core.task.TaskRejectedException;

/**
 * 全局异常处理。
 *
 * @RestControllerAdvice 的作用:把散落在各个 Controller 里的 try-catch 收拢到一处。
 * 业务代码只管抛异常,由这里统一决定 HTTP 状态码和返回体格式。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常:属于「意料之中的失败」,原因可以直接告诉用户,返回 400。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        log.warn("业务异常 [{}] {}", ex.getCode(), ex.getMessage());
        return ResponseEntity.badRequest().body(ApiError.of(ex.getCode(), ex.getMessage()));
    }

    /**
     * 上传文件超过限制。
     *
     * 这个异常是 Spring 在读取 multipart 请求时抛出的,发生在进入 Controller 之前,
     * 所以业务代码里 catch 不到它,只能在这里统一处理。
     * 返回 413 Payload Too Large 而不是 500 —— 这是客户端的问题,不是服务端故障。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("上传文件超过大小限制:{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of("FILE_TOO_LARGE", "文件超过大小上限,请压缩后重试"));
    }

    /**
     * 访问了不存在的接口。
     *
     * 为什么要单独处理它:下面那个兜底的 Exception 处理器会把所有异常变成 500。
     * 那样的话把 URL 打错一个字母,返回的也是「服务内部错误」,
     * 会让人误以为服务真的坏了。Spring 6.1 起,找不到静态资源会抛这个异常。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "接口不存在:" + ex.getResourcePath()));
    }

    /**
     * 兜底:任何没被上面接住的异常。
     *
     * 【重要】这里绝对不把 ex.getMessage() 返回给用户。
     * 未预期异常的原文里常常包含表名、SQL 片段、文件路径甚至连接串,
     * 直接返回等于免费给攻击者递一份情报。详细信息只写进服务端日志。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("未预期的异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "服务内部错误,请稍后重试"));
    }

    /**
     * 线程池队列已满,任务被拒绝。
     *
     * 返回 503 而不是 500 —— 这是「服务暂时过载」,不是「程序出错」。
     * 调用方看到 503 应该退避重试,看到 500 则应该排查代码。
     *
     * 【为什么要让它快速失败】
     * 如果线程池不设队列上限,请求会一直堆积到内存耗尽。
     * 快速拒绝至少让调用方立刻知道发生了什么,而不是等到连接超时。
     */
    @ExceptionHandler(TaskRejectedException.class)
    public ResponseEntity<ApiError> handleTaskRejected(TaskRejectedException ex) {
        log.warn("线程池已满,任务被拒绝:{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("SERVER_BUSY", "服务繁忙,请稍后重试"));
    }
}
