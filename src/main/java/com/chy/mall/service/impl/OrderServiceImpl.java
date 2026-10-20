package com.chy.mall.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.dto.CreateOrderItemRequest;
import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.entity.OrderItemDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.OrderStatus;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.OrderItemMapper;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.service.OrderService;
import com.chy.mall.util.LogSupport;
import com.chy.mall.vo.OrderItemVo;
import com.chy.mall.vo.OrderVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 订单入口：事务外识别幂等重试，调用独立创建事务；取消则用单一事务控制状态与库存。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {
    private static final int MAX_ORDER_ITEMS = 20;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductInventoryMapper productInventoryMapper;
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
                log.info("event=order_create_replayed buyerId={} orderId={} orderRequestId={} source=existing", buyerId, hisOrder.getId(), requestId);
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
            log.info("event=order_create_replayed buyerId={} orderId={} orderRequestId={} source=unique_key_recovery", buyerId, existingOrder.getId(), requestId);
            return buildExistingOrderVo(existingOrder);
        }
    }

    /**
     * 取消当前买家的订单，状态修改与所有商品库存恢复一起提交或回滚。
     * 只有本次成功转换状态的请求才能恢复库存，重复取消直接返回原订单。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVo cancel(Long buyerId, Long orderId) {
        if (buyerId == null || buyerId <= 0) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "用户ID必须大于0");
        }
        if (orderId == null || orderId <= 0) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "订单ID必须大于0");
        }

        // 先通过条件UPDATE竞争取消资格，不能根据先前查到的待支付状态直接恢复库存。
        LocalDateTime cancelTime = LocalDateTime.now().withNano(0);
        int affectedRows = markOrderCancelled(buyerId, orderId, cancelTime);
        if (affectedRows != 0 && affectedRows != 1) {
            throw new IllegalStateException("订单取消状态更新异常");
        }

        // 状态更新后再查询；同时限定买家，其他用户的订单也统一视为不存在。
        OrderDo order = orderMapper.selectOne(Wrappers.<OrderDo>lambdaQuery().eq(OrderDo::getId, orderId).eq(OrderDo::getBuyerId, buyerId));
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (affectedRows == 0) {
            if (order.getStatus() == OrderStatus.CANCELLED) {
                // 已取消属于幂等重试，不能再次增加库存或改写取消时间。
                log.info("event=order_cancel_replayed buyerId={} orderId={}", buyerId, orderId);
                return buildExistingOrderVo(order);
            }
            throw new BusinessException(ErrorCode.ORDER_CONFLICT, "订单当前状态不支持取消");
        }

        // 注册完成回调，后续库存恢复失败时也能记录真实回滚结果。
        LogSupport.transactionOutcome(log, "order_cancel", orderId);
        // 从已保存的订单明细取购买数量，按商品ID顺序更新库存，降低并发死锁风险。
        List<OrderItemDo> orderItems = orderItemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                .eq(OrderItemDo::getOrderId, orderId)
                .orderByAsc(OrderItemDo::getProductId));
        if (orderItems.isEmpty()) {
            throw new IllegalStateException("订单明细不存在，无法恢复库存");
        }
        for (OrderItemDo item : orderItems) {
            if (item.getProductId() == null || item.getProductId() <= 0
                    || item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new IllegalStateException("订单明细数据异常，无法恢复库存");
            }
            // 在数据库中执行stock = stock + quantity，避免先查库存再赋值造成并发覆盖。
            int restoredRows = productInventoryMapper.update(null, Wrappers.<ProductInventoryDo>lambdaUpdate()
                    .eq(ProductInventoryDo::getProductId, item.getProductId())
                    .setIncrBy(ProductInventoryDo::getStock, item.getQuantity()));
            if (restoredRows != 1) {
                log.error("event=inventory_restore_failed orderId={} productId={} affectedRows={}", orderId, item.getProductId(), restoredRows);
                // 抛出异常，撤销取消状态和本事务前面已经恢复的全部库存。
                throw new BusinessException(ErrorCode.PRODUCT_INVENTORY_MISSING);
            }
        }
        // 复用本次查出的明细组装快照，避免为返回结果再查询一次。
        return buildExistingOrderVo(order, orderItems);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderVo pay(Long buyerId, Long orderId) {
        if (buyerId == null || buyerId <= 0) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "用户ID必须大于0");
        }
        if (orderId == null || orderId <= 0) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "订单ID必须大于0");
        }
        LocalDateTime payTime = LocalDateTime.now().withNano(0);
        int affectedRows = markOrderPaid(buyerId, orderId, payTime);
        if (affectedRows != 1 && affectedRows != 0) {
            throw new IllegalStateException("订单支付异常");
        }
        OrderDo orderDo = orderMapper.selectOne(Wrappers.<OrderDo>lambdaQuery().eq(OrderDo::getId, orderId).eq(OrderDo::getBuyerId, buyerId));
        if (orderDo == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (affectedRows == 0) {
            if (orderDo.getStatus() == OrderStatus.PAID) {
                return buildExistingOrderVo(orderDo);
            }else{
                throw new BusinessException(ErrorCode.ORDER_CONFLICT,"当前订单状态无法支付");
            }
        }
        // 仅本次成功转换状态时注册回调，记录事务实际提交或回滚结果。
        LogSupport.transactionOutcome(log, "order_pay", orderId);
        return buildExistingOrderVo(orderDo);
    }

    /**
     * 尝试将当前用户的待支付订单更新为已取消。
     * 参数由公开取消入口统一校验；本方法只负责状态转换，不恢复库存。
     * 必须在公开取消方法的业务事务中调用，与库存恢复一起提交或回滚。
     *
     * @return 1表示本次成功转换状态，可继续恢复库存；0表示未转换，不得恢复库存
     */
    private int markOrderCancelled(Long buyerId, Long orderId, LocalDateTime cancelTime) {
        // 这里返回单条UPDATE的影响行数，不代表整个取消事务已经提交。
        return orderMapper.update(null, Wrappers.<OrderDo>lambdaUpdate()
                // 使用订单主键定位目标，不能与业务订单号orderNo混用。
                .eq(OrderDo::getId, orderId)
                // 将登录用户的ID放进更新条件，只能修改自己的订单。
                .eq(OrderDo::getBuyerId, buyerId)
                // 更新时再次检查旧状态，让并发请求竞争同一次状态转换。
                .eq(OrderDo::getStatus, OrderStatus.PENDING_PAYMENT)
                // 状态、取消时间和更新时间在同一条UPDATE中写入。
                .set(OrderDo::getStatus, OrderStatus.CANCELLED)
                .set(OrderDo::getCancelledAt, cancelTime)
                .set(OrderDo::getUpdatedAt, cancelTime));
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
        return buildExistingOrderVo(order, orderItems);
    }

    /** 使用已经查出的订单明细组装返回结果，保留历史商品快照。 */
    private OrderVo buildExistingOrderVo(OrderDo order, List<OrderItemDo> orderItems) {
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

    /**
     * 尝试将当前用户的待支付订单更新为已支付
     * @param buyerId
     * @param orderId
     * @param payTime
     * @return
     */
    private int markOrderPaid(Long buyerId, Long orderId, LocalDateTime payTime){
        return orderMapper.update(null, Wrappers.<OrderDo>lambdaUpdate()
                .eq(OrderDo::getId,orderId)
                .eq(OrderDo::getBuyerId,buyerId)
                .eq(OrderDo::getStatus,OrderStatus.PENDING_PAYMENT)
                .gt(OrderDo::getExpiresAt, payTime)
                .set(OrderDo::getStatus,OrderStatus.PAID)
                .set(OrderDo::getPaidAt,payTime)
                .set(OrderDo::getUpdatedAt,payTime)
        );
    }
}
