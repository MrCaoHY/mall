package com.chy.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单明细实体：保存下单时的商品快照，商品后续变更不覆盖历史快照。
 */
@Data
@TableName("order_items")
public class OrderItemDo {
    /** 数据库自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属订单 ID，关联 orders.id。 */
    private Long orderId;

    /** 购买商品 ID，关联 products.id。 */
    private Long productId;

    /** 下单时的商品 SKU 快照。 */
    private String skuSnapshot;

    /** 下单时的商品名称快照。 */
    private String nameSnapshot;

    /** 下单时的商品单价，由服务端读取并保存。 */
    private BigDecimal unitPrice;

    /** 购买数量，数据库要求大于 0。 */
    private Integer quantity;

    /** 当前商品行金额，由服务端按单价乘数量计算。 */
    private BigDecimal lineAmount;

    /** 创建时间，由数据库默认值生成。 */
    private LocalDateTime createdAt;

    /** 最后更新时间，由数据库 ON UPDATE 规则维护。 */
    private LocalDateTime updatedAt;
}
