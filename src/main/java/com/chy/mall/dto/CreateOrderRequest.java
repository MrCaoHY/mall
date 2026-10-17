package com.chy.mall.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/**
 * 一次下单意图：同一买家重试时复用requestId，商品清单由服务端规范化后比较指纹。
 */
@Data
public class CreateOrderRequest {
    /** 客户端下单意图号；重试复用，新意图重新生成UUID。 */
    @NotBlank(message = "请求号不能为空")
    @Pattern(
            regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
            message = "请求号必须是UUID格式"
    )
    private String requestId;
    /** 需要购买的商品，最多20项，每一项继续触发嵌套校验。 */
    @NotEmpty(message = "订单商品不能为空")
    @Size(max = 20,message = "订单不超过20项商品" )
    @Valid
    private List<@NotNull(message = "订单商品项不能为空")CreateOrderItemRequest> items;
}
