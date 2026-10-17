package com.chy.mall.vo;

import com.chy.mall.enums.ProductStatus;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品公开信息及当前库存，不暴露实体、Mapper或数据库内部字段。
 */
@Data
public class ProductVo {
    /** 商品主键。 */
    private Long id;
    /** 商品唯一编码。 */
    private String sku;
    /** 商品当前名称。 */
    private String name;
    /** 商品当前单价。 */
    private BigDecimal price;
    /** 商品当前销售状态。 */
    private ProductStatus status;
    /** 当前可售库存。 */
    private Integer stock;
}
