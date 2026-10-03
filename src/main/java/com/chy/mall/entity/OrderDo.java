package com.chy.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.chy.mall.enums.OrderStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单主表实体，仅负责数据库字段映射，不作为接口请求或返回模型。
 */
@Data
@TableName("orders")
public class OrderDo {
    /** 数据库自增主键，插入成功后由 MyBatis-Plus 回填。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 唯一业务订单号，由后续下单服务生成。 */
    private String orderNo;

    /** 购买用户 ID，由后续服务从已认证身份中取得。 */
    private Long buyerId;

    /** 订单状态，保存枚举名称，例如 PENDING_PAYMENT。 */
    private OrderStatus status;

    /** 订单总金额，由服务端汇总明细金额，不能使用浮点类型。 */
    private BigDecimal totalAmount;

    /** 未支付订单的到期时间。 */
    private LocalDateTime expiresAt;

    /** 支付完成时间，未支付时为 null。 */
    private LocalDateTime paidAt;

    /** 主动取消时间，未取消时为 null。 */
    private LocalDateTime cancelledAt;

    /** 创建时间，由数据库默认值生成。 */
    private LocalDateTime createdAt;

    /** 最后更新时间，由数据库 ON UPDATE 规则维护。 */
    private LocalDateTime updatedAt;
}
