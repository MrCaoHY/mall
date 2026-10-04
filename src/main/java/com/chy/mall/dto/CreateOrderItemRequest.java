package com.chy.mall.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class CreateOrderItemRequest {
    @NotNull(message = "商品id不能为空")
    @Positive(message = "商品id必须大于0")
    private Long productId;
    @NotNull(message = "商品数量不能为空")
    @Positive(message = "商品数量必须大于0")
    private Integer quantity;
}
