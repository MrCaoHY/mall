package com.chy.mall.exception;

import com.chy.mall.common.ApiResponse;
import com.chy.mall.util.LogSupport;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * HTTP异常出口：业务错误保留约定提示，系统异常统一返回通用500。
 * 日志只使用错误码/类型；不输出BindingResult、rejectedValue或原始异常消息。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        if (errorCode.getHttpStatus().is5xxServerError()) {
            log.error("event=business_failure errorCode={}", errorCode.getCode());
        } else if (errorCode.getHttpStatus().value() == 401 || errorCode.getHttpStatus().value() == 403) {
            // 登录失败、账号禁用、身份无效均可通过错误码区分，不记录用户名或密码。
            log.warn("event=business_rejected errorCode={}", errorCode.getCode());
        } else {
            // 库存不足、重复请求内容冲突等预期分支不当作系统故障告警。
            log.info("event=business_rejected errorCode={}", errorCode.getCode());
        }
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.<Void>failure(errorCode.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        log.info("event=request_validation_rejected errorCode={}", ErrorCode.REQUEST_INVALID.getCode());
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .findFirst()
                .orElse(ErrorCode.REQUEST_INVALID.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>failure(ErrorCode.REQUEST_INVALID.getCode(), message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(Exception exception) {
        log.info("event=request_binding_rejected exceptionType={}", exception.getClass().getName());
        return ResponseEntity.badRequest()
                .body(ApiResponse.<Void>failure(ErrorCode.REQUEST_INVALID.getCode(), ErrorCode.REQUEST_INVALID.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) throws Exception {
        // 保留Security的401/403处理，不能被通用500吞掉。
        if (exception instanceof AuthenticationException || exception instanceof AccessDeniedException) {
            throw exception;
        }
        // 保留框架的404、405、415等状态和Allow等响应头，避免通用兜底把它们变成500。
        if (exception instanceof ErrorResponse error && !error.getStatusCode().is5xxServerError()) {
            int status = error.getStatusCode().value();
            log.info("event=http_request_rejected status={} exceptionType={}", status, exception.getClass().getName());
            return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                    .body(ApiResponse.<Void>failure(status * 100, "请求无法处理"));
        }
        log.error("event=unhandled_exception exceptionType={}", exception.getClass().getName(), LogSupport.safeStackTrace(exception));
        return ResponseEntity.internalServerError()
                .body(ApiResponse.<Void>failure(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage()));
    }
}
