package com.chy.mall.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单商品明细返回模型，商品信息使用下单时保存的快照。
 */
@Data
public class OrderItemVo {
    /** 购买的商品 ID。 */
    private Long productId;

    /** 下单时的商品编码，由订单明细的 skuSnapshot 映射。 */
    private String sku;

    /** 下单时的商品名称，由订单明细的 nameSnapshot 映射。 */
    private String name;

    /** 下单时保存的商品单价。 */
    private BigDecimal unitPrice;

    /** 购买数量。 */
    private Integer quantity;

    /** 当前商品行金额，由服务端按单价乘数量计算。 */
    private BigDecimal lineAmount;
}
