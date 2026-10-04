package com.chy.mall.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrderRequest {
    @NotEmpty(message = "订单商品不能为空")
    @Size(max = 20,message = "订单不超过20项商品" )
    @Valid
    private List<@NotNull(message = "订单商品项不能为空")CreateOrderItemRequest> items;
}
