package com.chy.mall.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.dto.CreateOrderItemRequest;
import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.entity.OrderItemDo;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.OrderStatus;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.OrderItemMapper;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.service.OrderService;
import com.chy.mall.vo.OrderItemVo;
import com.chy.mall.vo.OrderVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {
    private static final int MAX_ORDER_ITEMS = 20;
    private static final BigDecimal MAX_ORDER_AMOUNT = new BigDecimal("9999999999999999.99");

    private final ProductInventoryMapper productInventoryMapper;
    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;


    private int deductStock(Long productId, Integer quantity) {
        if (productId == null || productId <= 0
                || quantity == null || quantity <= 0) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "商品ID和购买数量必须大于0");
        }

        LambdaUpdateWrapper<ProductInventoryDo> wrapper =
                Wrappers.<ProductInventoryDo>lambdaUpdate()
                        .eq(ProductInventoryDo::getProductId, productId)
                        .ge(ProductInventoryDo::getStock, quantity)
                        .setDecrBy(ProductInventoryDo::getStock, quantity);

        int rows = productInventoryMapper.update(null, wrapper);
        return rows;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public OrderVo create(Long buyerId, CreateOrderRequest request) {
        //校验request并提取订单商品转为treemap按照商品id排序
        TreeMap<Long, Integer> orderProductMap = validateAndBuildProductMap(buyerId, request);

        // 一次查询全部商品，避免循环查询产生N+1问题。
        List<ProductDo> productDos = productMapper.selectByIds(orderProductMap.keySet());
        BigDecimal totalAmount = BigDecimal.ZERO;
        //同时组装do和vo数据
        List<OrderItemDo> orderItems = new ArrayList<>();
        List<OrderItemVo> orderItemVos = new ArrayList<>();
        //把商品按id组装map避免后面构造快照每次查询列表·
        Map<Long, ProductDo> productById = productDos.stream().collect(Collectors.toMap(ProductDo::getId, p->p));
        // 按商品ID顺序校验和构造快照，返回结果顺序也保持稳定。
        for (Map.Entry<Long, Integer> entry : orderProductMap.entrySet()) {
            Long productId = entry.getKey();
            Integer quantity = entry.getValue();

            ProductDo product = productById.get(productId);
            if (product == null) {
                //说明数据库没有查到这个商品
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND,
                        "商品不存在，商品ID：" + productId);
            }
            if (product.getStatus() != ProductStatus.ON_SALE) {
                throw new BusinessException(ErrorCode.ORDER_CONFLICT,
                        "商品已下架，商品ID：" + productId);
            }

            BigDecimal lineAmount = product.getPrice().multiply(BigDecimal.valueOf(quantity));
            checkAmountRange(lineAmount, "商品行金额超出允许范围，商品ID：" + productId);
            totalAmount = totalAmount.add(lineAmount);
            checkAmountRange(totalAmount, "订单总金额超出允许范围");
            //组装订单每个商品的list
            OrderItemDo orderItem = new OrderItemDo();
            orderItem.setProductId(productId);
            orderItem.setSkuSnapshot(product.getSku());
            orderItem.setNameSnapshot(product.getName());
            orderItem.setUnitPrice(product.getPrice());
            orderItem.setQuantity(quantity);
            orderItem.setLineAmount(lineAmount);
            orderItems.add(orderItem);

            OrderItemVo orderItemVo = new OrderItemVo();
            orderItemVo.setProductId(productId);
            orderItemVo.setSku(product.getSku());
            orderItemVo.setName(product.getName());
            orderItemVo.setUnitPrice(product.getPrice());
            orderItemVo.setQuantity(quantity);
            orderItemVo.setLineAmount(lineAmount);
            orderItemVos.add(orderItemVo);
        }
        //遍历完商品没有问题开始减库存
        // TreeMap保证不同订单以相同商品ID顺序加锁，降低并发死锁风险。
        for (Map.Entry<Long, Integer> entry : orderProductMap.entrySet()) {
            int affectedRows = deductStock(entry.getKey(), entry.getValue());
            if (affectedRows != 1) {
                throw new BusinessException(ErrorCode.ORDER_CONFLICT,
                        "库存不足或库存记录不存在，商品ID：" + entry.getKey());
            }
        }
        //库存减完没有问题开始保存订单
        LocalDateTime createTime = LocalDateTime.now().withNano(0);
        OrderDo order = new OrderDo();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setBuyerId(buyerId);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setTotalAmount(totalAmount);
        order.setExpiresAt(createTime.plusMinutes(30));
        order.setCreatedAt(createTime);
        order.setUpdatedAt(createTime);
        if (orderMapper.insert(order) != 1 || order.getId() == null) {
            throw new IllegalStateException("订单保存失败");
        }
        orderItems.forEach(orderItem -> orderItem.setOrderId(order.getId()));
        //批量插入订单明细
        int insertedRows = orderItemMapper.batchInsert(orderItems);
        if (insertedRows != orderItems.size()) {
            throw new IllegalStateException("订单明细保存失败");
        }
        //构建返回vo
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

    /**
     * Service需要保护自己的公开入口，不能只依赖Controller触发DTO校验。
     */
    private TreeMap<Long, Integer> validateAndBuildProductMap(Long buyerId, CreateOrderRequest request) {
        if (buyerId == null || buyerId <= 0) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "用户ID必须大于0");
        }
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
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

    private void checkAmountRange(BigDecimal amount, String message) {
        if (amount.signum() <= 0 || amount.compareTo(MAX_ORDER_AMOUNT) > 0) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, message);
        }
    }
}
