package com.chy.mall.exception;

import com.chy.mall.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：统一处理业务异常和Bean Validation请求校验异常。
 * 数据库异常、编程错误等未知异常不在这里伪装成业务错误，仍由系统返回500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.<Void>failure(errorCode.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .findFirst()
                .orElse(ErrorCode.REQUEST_INVALID.getMessage());
        return ResponseEntity.status(ErrorCode.REQUEST_INVALID.getHttpStatus())
                .body(ApiResponse.<Void>failure(ErrorCode.REQUEST_INVALID.getCode(), message));
    }
}
