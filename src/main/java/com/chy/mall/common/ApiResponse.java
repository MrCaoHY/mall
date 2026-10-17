package com.chy.mall.common;

import lombok.Data;

/**
 * 统一HTTP响应体。HTTP状态表达请求结果，code表达具体业务原因，data承载公开数据。
 */
@Data
public class ApiResponse <T>{
    /** 0表示成功，其他值为业务错误码。 */
    private Integer code;
    /** 稳定的对外提示，不包含内部堆栈和敏感信息。 */
    private String message;
    /** 成功返回公开业务数据，失败通常为null。 */
    private T data;
    public static <T> ApiResponse<T> success(T data){
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(0);
        response.setMessage("SUCCESS");
        response.setData(data);
        return response;
    }

    // 错误响应沿用code/message/data结构，HTTP状态由Controller或异常处理器决定。
    public static <T> ApiResponse<T> failure(Integer code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(code);
        response.setMessage(message);
        response.setData(null);
        return response;
    }
}
