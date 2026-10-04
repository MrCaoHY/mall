package com.chy.mall.control;

import com.chy.mall.common.ApiResponse;
import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.service.OrderService;
import com.chy.mall.vo.OrderVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口：买家身份来自Spring Security认证结果，不接受客户端传入buyerId。
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    /**
     * 创建订单。当前学习阶段约定认证用户名为正数买家ID。
     */
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<OrderVo>> create(
            Authentication authentication,
            @Valid @RequestBody CreateOrderRequest request) {
        Long buyerId = resolveBuyerId(authentication);
        OrderVo order = orderService.create(buyerId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(order));
    }

    private Long resolveBuyerId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(ErrorCode.BUYER_IDENTITY_INVALID, "当前请求尚未登录");
        }

        try {
            Long buyerId = Long.valueOf(authentication.getName());
            if (buyerId <= 0) {
                throw new BusinessException(ErrorCode.BUYER_IDENTITY_INVALID);
            }
            return buyerId;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.BUYER_IDENTITY_INVALID);
        }
    }
}
