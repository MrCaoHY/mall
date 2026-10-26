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
    /** 执行动作的用户ID。 */
    private Long operatorId;
    /** 创建、支付或取消。 */
    private OrderOperationAction action;
    /** 动作前状态，创建记录为空。 */
    private OrderStatus fromStatus;
    /** 动作后的状态。 */
    private OrderStatus toStatus;
    /** 服务端动作时间。 */
    private LocalDateTime createdAt;
}
