package com.chy.mall.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 商品编辑表单，每次完整提交名称和价格；不允许借此修改SKU、状态或库存。 */
@Data
public class UpdateProductRequest {
    /** 商品当前名称，订单中的历史名称快照不随之改变。 */
    @NotBlank(message = "商品名称不能为空白")
    @Size(max = 100, message = "商品名称最长100个字符")
    private String name;

    /** 销售价格，与products.price的DECIMAL(12,2)精度保持一致。 */
    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格不能小于0.01")
    @Digits(integer = 10, fraction = 2, message = "价格最多10位整数、2位小数")
    private BigDecimal price;
}
