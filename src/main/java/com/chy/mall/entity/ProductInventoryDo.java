package com.chy.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
/**
 * 商品库存表映射，productId同时是主键和商品关联键；加减库存必须使用数据库原子更新。
 */
@Data
@TableName("product_inventory")
public class ProductInventoryDo {
    /** 商品ID，同时作为库存主键。 */
    @TableId(value = "product_id", type = IdType.INPUT)
    private Long productId;
    /** 可售库存，允许0；下单扣减、取消加回。 */
    private Integer stock;
    /** 数据库维护的创建时间。 */
    private LocalDateTime createdAt;
    /** 数据库维护的最后更新时间。 */
    private LocalDateTime updatedAt;
}
