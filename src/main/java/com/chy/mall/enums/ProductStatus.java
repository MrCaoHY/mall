package com.chy.mall.enums;


import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ProductStatus {
    ON_SALE("在售"),
    OFF_SALE("下架");
    private final String description;

}
