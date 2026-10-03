package com.chy.mall.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单状态：枚举名称与数据库 status 字段保存的字符串一致。
 */
@Getter
@AllArgsConstructor
public enum OrderStatus {
    PENDING_PAYMENT("待支付"),
    PAID("已支付"),
    CANCELLED("已取消"),
    CLOSED("超时关闭");

    private final String description;
}
