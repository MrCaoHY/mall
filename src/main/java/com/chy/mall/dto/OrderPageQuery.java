package com.chy.mall.dto;

import com.chy.mall.enums.OrderStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 自己的订单分页参数；买家身份来自登录结果，不允许请求指定。 */
@Data
public class OrderPageQuery {
    /** 页码从1开始，不传时查询第一页。 */
    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码不能小于1")
    private Integer page = 1;

    /** 每页默认10条，最多100条。 */
    @NotNull(message = "每页数量不能为空")
    @Min(value = 1, message = "每页数量不能小于1")
    @Max(value = 100, message = "每页数量不能超过100")
    private Integer size = 10;

    /** 可选状态，不传则查询自己的全部状态订单。 */
    private OrderStatus status;
}
