package com.chy.mall.exception;

import java.io.Serial;

// 商品存在却没有库存记录，不等于商品不存在，也不等于库存为0。
public class ProductInventoryMissingException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Long productId;

    public ProductInventoryMissingException(Long productId) {
        super("商品库存数据异常");
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
