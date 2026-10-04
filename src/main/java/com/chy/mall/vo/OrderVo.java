package com.chy.mall.vo;

import com.chy.mall.enums.OrderStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单返回模型，展示订单基本信息及商品明细，不直接暴露数据库实体。
 */
@Data
public class OrderVo {
    /** 订单 ID。 */
    private Long id;

    /** 服务端生成的唯一业务订单号。 */
    private String orderNo;

    /** 当前订单状态。 */
    private OrderStatus status;

    /** 订单总金额，由服务端汇总商品行金额。 */
    private BigDecimal totalAmount;

    /** 待支付订单的到期时间。 */
    private LocalDateTime expiresAt;

    /** 订单创建时间。 */
    private LocalDateTime createdAt;

    /** 当前订单的商品明细列表。 */
    private List<OrderItemVo> items;
}
