package com.chy.mall.exception;

import com.chy.mall.common.ApiResponse;
import com.chy.mall.control.ProductController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
// 本次仅处理商品Controller抛出的两种详情异常，不吞掉其他数据库或校验错误。
@RestControllerAdvice(assignableTypes = ProductController.class)
public class ProductExceptionHandler {
    private static final int PRODUCT_NOT_FOUND_CODE = 40401;
    private static final int PRODUCT_INVENTORY_MISSING_CODE = 50001;

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleProductNotFound(ProductNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.<Void>failure(PRODUCT_NOT_FOUND_CODE, exception.getMessage()));
    }

    @ExceptionHandler(ProductInventoryMissingException.class)
    public ResponseEntity<ApiResponse<Void>> handleInventoryMissing(ProductInventoryMissingException exception) {
        // 只在异常处理层记录一次，保留定位ID，不向客户端暴露SQL或堆栈。
        log.error("商品存在但库存记录缺失，productId={}", exception.getProductId());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.<Void>failure(PRODUCT_INVENTORY_MISSING_CODE, exception.getMessage()));
    }
}
