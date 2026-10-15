package com.chy.mall.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrderRequest {
    @NotBlank(message = "请求号不能为空")
    @Pattern(
            regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
            message = "请求号必须是UUID格式"
    )
    private String requestId;
    @NotEmpty(message = "订单商品不能为空")
    @Size(max = 20,message = "订单不超过20项商品" )
    @Valid
    private List<@NotNull(message = "订单商品项不能为空")CreateOrderItemRequest> items;
}
