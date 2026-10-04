package com.chy.mall.exception;

import java.io.Serial;
import java.util.Objects;

/**
 * 统一业务异常。业务代码只选择错误类型，异常处理器负责转换HTTP响应。
 */
public class BusinessException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode不能为空");
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
