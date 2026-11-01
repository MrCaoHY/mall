package com.chy.mall.dto;

import com.chy.mall.enums.ProductStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 管理员设置商品的目标销售状态；重复请求不会反转状态。 */
@Data
public class UpdateProductStatusRequest {
    /** ON_SALE上架，OFF_SALE下架，不影响既有订单和库存。 */
    @NotNull(message = "商品状态不能为空")
    private ProductStatus status;
}
