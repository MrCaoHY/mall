package com.chy.mall.exception;

import org.springframework.http.HttpStatus;

/**
 * 系统对外错误码。错误码、HTTP状态和默认提示统一在这里维护。
 */
public enum ErrorCode {
    REQUEST_INVALID(40001, HttpStatus.BAD_REQUEST, "请求参数不合法"),
    BUYER_IDENTITY_INVALID(40301, HttpStatus.FORBIDDEN, "当前账号未绑定有效买家ID"),
    PRODUCT_NOT_FOUND(40401, HttpStatus.NOT_FOUND, "商品不存在"),
    ORDER_CONFLICT(40901, HttpStatus.CONFLICT, "订单当前无法创建"),
    PRODUCT_INVENTORY_MISSING(50001, HttpStatus.INTERNAL_SERVER_ERROR, "商品库存数据异常");

    private final int code;
    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(int code, HttpStatus httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
