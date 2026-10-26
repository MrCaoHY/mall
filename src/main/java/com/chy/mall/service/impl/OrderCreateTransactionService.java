package com.chy.mall.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.entity.OrderItemDo;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.OrderStatus;
import com.chy.mall.enums.OrderOperationAction;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.OrderItemMapper;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.util.LogSupport;
import com.chy.mall.vo.OrderItemVo;
import com.chy.mall.vo.OrderVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 独立Bean承载新订单事务，唯一键裁决、库存扣减和明细保存一起提交；异常先回滚再交回入口。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCreateTransactionService {
    private static final BigDecimal MAX_ORDER_AMOUNT = new BigDecimal("9999999999999999.99");

    private final ProductInventoryMapper productInventoryMapper;
    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderOperationLogService operationLogService;

    /** 新事务先写订单主表竞争幂等唯一键，胜出后再扣库存和保存快照。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public OrderVo createNewOrder(Long buyerId, String requestId, String requestFingerprint, TreeMap<Long, Integer> orderProductMap) {
        // 一次查询全部商品，避免循环查询产生N+1问题。
        List<ProductDo> productDos = productMapper.selectByIds(orderProductMap.keySet());
        BigDecimal totalAmount = BigDecimal.ZERO;
        //同时组装do和vo数据
        List<OrderItemDo> orderItems = new ArrayList<>();
        List<OrderItemVo> orderItemVos = new ArrayList<>();
        //把商品按id组装map避免后面构造快照每次查询列表·
        Map<Long, ProductDo> productById = productDos.stream().collect(Collectors.toMap(ProductDo::getId, p -> p));
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

        // 先插入订单主表，让幂等唯一键在任何库存写入前裁决并发重复请求。
        LocalDateTime createTime = LocalDateTime.now().withNano(0);
        OrderDo order = new OrderDo();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setBuyerId(buyerId);
        order.setRequestId(requestId);
        order.setRequestFingerprint(requestFingerprint);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setTotalAmount(totalAmount);
        order.setExpiresAt(createTime.plusMinutes(30));
        order.setCreatedAt(createTime);
        order.setUpdatedAt(createTime);
        if (orderMapper.insert(order) != 1 || order.getId() == null) {
            throw new IllegalStateException("订单保存失败");
        }

        // 回调在事务真正提交/回滚后记录结果，不能在return前声称创建成功。
        LogSupport.transactionOutcome(log, "order_create", order.getId());
        // TreeMap保证不同订单以相同商品ID顺序加锁，降低并发死锁风险。
        for (Map.Entry<Long, Integer> entry : orderProductMap.entrySet()) {
            int affectedRows = deductStock(entry.getKey(), entry.getValue());
            if (affectedRows != 1) {
                log.info("event=order_stock_rejected buyerId={} productId={}", buyerId, entry.getKey());
                throw new BusinessException(ErrorCode.ORDER_CONFLICT,
                        "库存不足或库存记录不存在，商品ID：" + entry.getKey());
            }
        }

        orderItems.forEach(orderItem -> orderItem.setOrderId(order.getId()));
        //批量插入订单明细
        int insertedRows = orderItemMapper.batchInsert(orderItems);
        if (insertedRows != orderItems.size()) {
            throw new IllegalStateException("订单明细保存失败");
        }
        // 主表、库存、明细和CREATE流水必须一起提交，流水失败不能留下半张订单。
        operationLogService.recordOperation(order.getId(), buyerId, OrderOperationAction.CREATE, createTime);
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

    /** stock >= quantity与stock减法在同一UPDATE中执行，不能先查询库存再计算。 */
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

        return productInventoryMapper.update(null, wrapper);
    }

    /** 与数据库金额精度保持一致，逐行和累加后都检查，避免写库时溢出。 */
    private void checkAmountRange(BigDecimal amount, String message) {
        if (amount.signum() <= 0 || amount.compareTo(MAX_ORDER_AMOUNT) > 0) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, message);
        }
    }
}
