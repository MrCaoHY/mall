package com.chy.mall.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 订单真实成功动作；状态转换在这里集中定义，避免调用方写错前后状态。 */
@Getter
@RequiredArgsConstructor
public enum OrderOperationAction {
    /** 新订单创建成功，创建之前没有订单状态。 */
    CREATE(null, OrderStatus.PENDING_PAYMENT),
    /** 待支付订单成功支付；幂等重试不是新动作。 */
    PAY(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID),
    /** 待支付订单成功取消，并在同一事务恢复库存。 */
    CANCEL(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED);

    /** 动作前状态，只有CREATE允许为空。 */
    private final OrderStatus fromStatus;
    /** 动作成功后的状态。 */
    private final OrderStatus toStatus;
}
