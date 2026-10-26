package com.chy.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.chy.mall.enums.OrderOperationAction;
import com.chy.mall.enums.OrderStatus;
import lombok.Data;

import java.time.LocalDateTime;

/** 订单业务流水，只在真实成功动作所在事务内追加，不修改已有记录。 */
@Data
@TableName("order_operation_logs")
public class OrderOperationLogDo {
    /** 流水自增主键，同一秒内用它稳定排列动作先后。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 订单数据库主键，不是orderNo或客户端requestId。 */
    private Long orderId;
    /** 用户动作使用可信用户ID，系统关闭使用0；不能接受客户端任意指定。 */
    private Long operatorId;
    /** 成功动作名称：CREATE、PAY、CANCEL或CLOSE。 */
    private OrderOperationAction action;
    /** 动作前状态，创建订单时为null。 */
    private OrderStatus fromStatus;
    /** 动作成功后的状态。 */
    private OrderStatus toStatus;
    /** 服务端业务动作时间，与对应创建、支付、取消或关闭时间使用同一值。 */
    private LocalDateTime createdAt;
}
