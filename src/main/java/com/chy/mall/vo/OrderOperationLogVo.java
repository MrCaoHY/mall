package com.chy.mall.vo;

import com.chy.mall.enums.OrderOperationAction;
import com.chy.mall.enums.OrderStatus;
import lombok.Data;

import java.time.LocalDateTime;

/** 订单操作记录响应；订单归属由Service检查，不直接暴露数据库实体。 */
@Data
public class OrderOperationLogVo {
    /** 流水主键。 */
    private Long id;
    /** 所属订单主键。 */
    private Long orderId;
    /** 操作者编号：用户动作为用户ID，系统关闭为0。 */
    private Long operatorId;
    /** 创建、支付、取消或系统超时关闭。 */
    private OrderOperationAction action;
    /** 动作前状态，创建记录为空。 */
    private OrderStatus fromStatus;
    /** 动作后的状态。 */
    private OrderStatus toStatus;
    /** 服务端动作时间。 */
    private LocalDateTime createdAt;
}
