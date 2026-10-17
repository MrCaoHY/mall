package com.chy.mall.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 客户端提交的商品和数量，不接受客户端指定价格、行金额或商品快照。
 */
@Data
public class CreateOrderItemRequest {
    /** 购买商品的数据库主键。 */
    @NotNull(message = "商品id不能为空")
    @Positive(message = "商品id必须大于0")
    private Long productId;
    /** 购买数量，必须为正整数。 */
    @NotNull(message = "商品数量不能为空")
    @Positive(message = "商品数量必须大于0")
    private Integer quantity;
}
