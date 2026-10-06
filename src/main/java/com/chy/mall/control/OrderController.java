package com.chy.mall.control;

import com.chy.mall.common.ApiResponse;
import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.service.OrderService;
import com.chy.mall.vo.OrderVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口：暂用固定开发买家ID；实现权限模块后改为从认证身份获取。
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    /**
     * 创建订单。开发买家ID固定为1，仍不接受客户端传入buyerId。
     */
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<OrderVo>> create(@Valid @RequestBody CreateOrderRequest request) {
        // TODO 接入权限模块时，从已认证用户取得buyerId。
        Long buyerId = 1L;
        OrderVo order = orderService.create(buyerId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(order));
    }
}
