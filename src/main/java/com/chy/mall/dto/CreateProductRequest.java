package com.chy.mall.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;


/**
 * 新增商品请求，金额最多两位小数、初始库存允许为0，状态由服务端指定。
 */
@Data
public class CreateProductRequest {
    /** 商品唯一编码，不允许空白且最长32字符。 */
    @NotBlank(message = "不允许为空")
    @Size(max = 32, message = "最长32个字符")
    private String sku;
    /** 商品名称，最长100字符。 */
    @NotBlank(message = "不允许为空")
    @Size(max = 100, message = "最长不超过100字符")
    private String name;
    /** 价格范围与数据库DECIMAL(12,2)保持一致。 */
    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格不能小于0.01")
    @Digits(integer = 10, fraction = 2,
            message = "价格最多10位整数、2位小数")
    private BigDecimal price;
    /** 商品首次建立时的库存，可为0。 */
    @NotNull(message = "库存不能为空")
    @PositiveOrZero(message = "库存不允许为负数")
    private Integer initialStock;
}
