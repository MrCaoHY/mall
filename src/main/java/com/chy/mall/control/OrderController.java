 package com.chy.mall.control;

import com.chy.mall.common.ApiResponse;
import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.dto.OrderPageQuery;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.security.AuthenticatedUser;
import com.chy.mall.service.OrderService;
import com.chy.mall.vo.OrderVo;
import com.chy.mall.vo.PageVo;
import com.chy.mall.vo.OrderOperationLogVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单接口：从JWT认证结果取买家身份，ID合法性与订单归属由Service统一校验。
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
        OrderVo order = orderService.create(buyerId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(order));
    }

    /**
     * POST /orders/{orderId}/cancel，无请求体；买家ID只取认证结果，客户端不能指定。
     * 首次取消和重复取消均返回200；库存是否恢复由Service的条件更新结果决定。
     */
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<ApiResponse<OrderVo>> cancel(@PathVariable @Positive(message = "订单ID必须大于0") Long orderId, @AuthenticationPrincipal AuthenticatedUser currentUser) {
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Long buyerId = currentUser.userId();
        return ResponseEntity.ok(ApiResponse.success(orderService.cancel(buyerId, orderId)));
    }

    /**
     * POST /orders/{orderId}/pay，无请求体；买家ID仅取当前认证身份。
     * 首次支付和重复支付均返回200，状态转换及首次支付时间由Service维护。
     */
    @PostMapping("/{orderId}/pay")
    public ResponseEntity<ApiResponse<OrderVo>> pay(@PathVariable @Positive(message = "订单ID必须大于0") Long orderId, @AuthenticationPrincipal AuthenticatedUser currentUser) {
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Long buyerId = currentUser.userId();
        return ResponseEntity.ok(ApiResponse.success(orderService.pay(buyerId, orderId)));
    }

    /**
     * 查询自己订单的成功动作流水，不接收客户端提供的操作者ID。
     * 返回按业务时间和流水主键升序排列的记录；历史无流水的订单返回空列表。
     */
    @GetMapping("/{orderId}/operations")
    public ResponseEntity<ApiResponse<List<OrderOperationLogVo>>> operations(
            @PathVariable @Positive(message = "订单ID必须大于0") Long orderId,
            @AuthenticationPrincipal AuthenticatedUser currentUser) {
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Long buyerId = currentUser.userId();
        return ResponseEntity.ok(ApiResponse.success(orderService.listOperations(buyerId, orderId)));
    }

    /** GET /orders：使用查询参数分页；客户端不能指定要查询的买家。 */
    @GetMapping
    public ResponseEntity<ApiResponse<PageVo<OrderVo>>> page(
            @Valid @ModelAttribute OrderPageQuery query,
            @AuthenticationPrincipal AuthenticatedUser currentUser) {
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Long buyerId = currentUser.userId();
        return ResponseEntity.ok(ApiResponse.success(orderService.page(buyerId, query)));
    }

    /** 查询当前认证买家的单笔订单，包含下单时的商品快照。 */
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderVo>> orderDetail(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable @Positive(message = "订单ID必须大于0") Long orderId) {
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Long buyerId = currentUser.userId();
        return ResponseEntity.ok(ApiResponse.success(orderService.findById(buyerId, orderId)));
    }
}
