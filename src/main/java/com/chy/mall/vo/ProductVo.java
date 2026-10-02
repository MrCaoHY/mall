package com.chy.mall.vo;

import com.chy.mall.enums.ProductStatus;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductVo {
    private Long id;
    private String sku;
    private String name;
    private BigDecimal price;
    private ProductStatus status;
    private Integer stock;
}
