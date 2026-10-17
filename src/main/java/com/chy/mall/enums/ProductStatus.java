package com.chy.mall.enums;


import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 商品销售状态；枚举名称与数据库保存值一致，description仅用于说明。
 */
@Getter
@AllArgsConstructor
public enum ProductStatus {
    ON_SALE("在售"),
    OFF_SALE("下架");
    private final String description;

}
