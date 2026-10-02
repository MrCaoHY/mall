package com.chy.mall.common;

import lombok.Data;

@Data
public class ApiResponse <T>{
    private Integer code;
    private String message;
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
