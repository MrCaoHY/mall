package com.chy.mall.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.dto.CreateOrderItemRequest;
import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.entity.OrderItemDo;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.OrderItemMapper;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.service.OrderService;
import com.chy.mall.vo.OrderItemVo;
import com.chy.mall.vo.OrderVo;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private static final int MAX_ORDER_ITEMS = 20;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderCreateTransactionService orderCreateTransactionService;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Override
    public OrderVo create(Long buyerId, CreateOrderRequest request) {
        //校验request并提取订单商品转为treemap按照商品id排序
        TreeMap<Long, Integer> orderProductMap = validateAndBuildProductMap(buyerId, request);
        String requestId = UUID.fromString(request.getRequestId()).toString();
        //生成产品指纹
        String requestFingerprint = buildRequestFingerprint(orderProductMap);
        // 在这里查询已有订单
        OrderDo hisOrder = orderMapper.selectOne(Wrappers.<OrderDo>lambdaQuery().eq(OrderDo::getBuyerId, buyerId).eq(OrderDo::getRequestId, requestId));
        //如果查到已有订单 验证商品列表指纹
        if (hisOrder != null) {
            if (Objects.equals(hisOrder.getRequestFingerprint(), requestFingerprint)) {
                //指纹相同查询已有订单明细返回
                return buildExistingOrderVo(hisOrder);
            }else{
                throw new BusinessException(ErrorCode.ORDER_CONFLICT,"同一请求号不能提交不同的订单内容");
            }
        }
        try {
            return orderCreateTransactionService.createNewOrder(buyerId, requestId, requestFingerprint, orderProductMap);
        } catch (DuplicateKeyException e) {
            // 跨Bean调用已结束，内层创建事务已回滚；此处重新读取成功提交的订单。
            OrderDo existingOrder = orderMapper.selectOne(Wrappers.<OrderDo>lambdaQuery().eq(OrderDo::getBuyerId, buyerId).eq(OrderDo::getRequestId, requestId));
            if (existingOrder == null) {
                // 可能是订单号等其他唯一键冲突，保留原异常。
                throw e;
            }
            if (!Objects.equals(existingOrder.getRequestFingerprint(), requestFingerprint)) {
                throw new BusinessException(ErrorCode.ORDER_CONFLICT,"同一请求号不能提交不同的订单内容");
            }
            return buildExistingOrderVo(existingOrder);
        }
    }

    /**
     * Service需要保护自己的公开入口，不能只依赖Controller触发DTO校验。
     */
    private TreeMap<Long, Integer> validateAndBuildProductMap(Long buyerId, CreateOrderRequest request) {
        if (buyerId == null || buyerId <= 0) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "用户ID必须大于0");
        }

        if (request == null) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "订单请求不能为空");
        }
        if (!isValidRequestId(request.getRequestId())) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "请求号必须是UUID格式");
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "订单商品不能为空");
        }
        if (request.getItems().size() > MAX_ORDER_ITEMS) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "订单不超过20项商品");
        }

        TreeMap<Long, Integer> orderProductMap = new TreeMap<>();
        for (CreateOrderItemRequest item : request.getItems()) {
            if (item == null || item.getProductId() == null || item.getProductId() <= 0
                    || item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new BusinessException(ErrorCode.REQUEST_INVALID,
                        "商品ID和购买数量必须大于0");
            }
            if (orderProductMap.putIfAbsent(item.getProductId(), item.getQuantity()) != null) {
                throw new BusinessException(ErrorCode.REQUEST_INVALID,
                        "同一商品不能重复提交，商品ID：" + item.getProductId());
            }
        }
        return orderProductMap;
    }

    private boolean isValidRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        try {
            return UUID.fromString(requestId).toString().equalsIgnoreCase(requestId);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String buildRequestFingerprint(TreeMap<Long, Integer> orderProductMap) {
        StringBuilder stringBuilder = new StringBuilder();
        for (Map.Entry<Long, Integer> entry : orderProductMap.entrySet()) {
            stringBuilder.append(entry.getKey()).append(':').append(entry.getValue()).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(stringBuilder.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持SHA-256", exception);
        }
    }

    private OrderVo buildExistingOrderVo(OrderDo order) {
        List<OrderItemDo> orderItems = orderItemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                .eq(OrderItemDo::getOrderId, order.getId())
                .orderByAsc(OrderItemDo::getProductId));
        List<OrderItemVo> orderItemVos = new ArrayList<>(orderItems.size());
        for (OrderItemDo orderItem : orderItems) {
            OrderItemVo orderItemVo = new OrderItemVo();
            orderItemVo.setProductId(orderItem.getProductId());
            orderItemVo.setSku(orderItem.getSkuSnapshot());
            orderItemVo.setName(orderItem.getNameSnapshot());
            orderItemVo.setUnitPrice(orderItem.getUnitPrice());
            orderItemVo.setQuantity(orderItem.getQuantity());
            orderItemVo.setLineAmount(orderItem.getLineAmount());
            orderItemVos.add(orderItemVo);
        }
        OrderVo result = new OrderVo();
        result.setId(order.getId());
        result.setOrderNo(order.getOrderNo());
        result.setStatus(order.getStatus());
        result.setTotalAmount(order.getTotalAmount());
        result.setExpiresAt(order.getExpiresAt());
        result.setCreatedAt(order.getCreatedAt());
        result.setItems(orderItemVos);
        return result;
    }
}
