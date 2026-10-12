package com.chy.mall.control;

import com.chy.mall.common.ApiResponse;
import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.security.AuthenticatedUser;
import com.chy.mall.service.OrderService;
import com.chy.mall.vo.OrderVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口：买家身份来自当前请求的JWT认证结果。
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    /**
     * 创建订单。使用当前认证用户ID作为买家ID。
     */
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<OrderVo>> create(@Valid @RequestBody CreateOrderRequest request, @AuthenticationPrincipal AuthenticatedUser currentUser) {
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Long buyerId = currentUser.userId();
        if (buyerId == null || buyerId <= 0) {
            throw new BusinessException(ErrorCode.BUYER_IDENTITY_INVALID);
        }
        OrderVo order = orderService.create(buyerId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(order));
    }
}
