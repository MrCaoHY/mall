package com.chy.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.chy.mall.enums.ProductStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * products表映射。金额使用BigDecimal；时间由数据库默认值和更新规则维护。
 */
@TableName("products")
@Data
public class ProductDo {
    /** 商品自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 商品唯一SKU，唯一性由数据库最终保证。 */
    private String sku;
    /** 当前商品名称，历史订单使用独立快照。 */
    private String name;
    /** 当前销售单价，禁止用double参与金额计算。 */
    private BigDecimal price;
    /** 当前上架状态，只有ON_SALE允许下单。 */
    private ProductStatus status;
    /** 数据库维护的创建时间。 */
    private LocalDateTime createdAt;
    /** 数据库维护的最后更新时间。 */
    private LocalDateTime updatedAt;
}
