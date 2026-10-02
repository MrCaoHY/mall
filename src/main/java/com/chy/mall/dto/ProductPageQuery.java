package com.chy.mall.dto;

import com.chy.mall.enums.ProductStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProductPageQuery {
    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码不能小于1")
    private Integer page = 1;

    @NotNull(message = "每页数量不能为空")
    @Min(value = 1, message = "每页数量不能小于1")
    @Max(value = 100, message = "每页数量不能超过100")
    private Integer size = 10;

    // 可选：名称或SKU包含关键词；忽略首尾空白。
    @Size(max = 100, message = "关键词最长100个字符")
    private String keyword;

    // 可选：SKU精确匹配，遵循数据库已有的字符排序规则。
    @Size(max = 32, message = "SKU最长32个字符")
    private String sku;

    // 不传则不过滤状态；非法枚举值由请求参数绑定返回400。
    private ProductStatus status;

    // 配合SQL的ESCAPE '!'，把用户输入的%、_、!按普通文字搜索。
    // 这仍然是绑定参数，不将用户输入拼接进SQL语句。
    public String getKeywordPattern() {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String escaped = keyword.trim()
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    public String getSkuFilter() {
        return sku == null || sku.isBlank() ? null : sku.trim();
    }
}
