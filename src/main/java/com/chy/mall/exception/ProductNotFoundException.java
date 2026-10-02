package com.chy.mall.exception;

import java.io.Serial;

// 业务层只描述“商品不存在”，由HTTP异常处理器决定返回404。
public class ProductNotFoundException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final Long productId;

    public ProductNotFoundException(Long productId) {
        super("商品不存在");
        this.productId = productId;
    }

    public Long getProductId() {
        return productId;
    }
}
