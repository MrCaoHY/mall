package com.chy.mall.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;


@Data
public class CreateProductRequest {
    @NotBlank(message = "不允许为空")
    @Size(max = 32, message = "最长32个字符")
    private String sku;
    @NotBlank(message = "不允许为空")
    @Size(max = 100, message = "最长不超过100字符")
    private String name;
    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格不能小于0.01")
    @Digits(integer = 10, fraction = 2,
            message = "价格最多10位整数、2位小数")
    private BigDecimal price;
    @NotNull(message = "库存不能为空")
    @PositiveOrZero(message = "库存不允许为负数")
    private Integer initialStock;
}
