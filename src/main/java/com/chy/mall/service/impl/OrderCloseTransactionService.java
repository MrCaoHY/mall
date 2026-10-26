package com.chy.mall.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.entity.OrderItemDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.OrderOperationAction;
import com.chy.mall.enums.OrderStatus;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.OrderItemMapper;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.util.LogSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 单笔系统关闭：状态、全部库存恢复和成功流水必须在同一事务中提交或回滚。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCloseTransactionService {
    /** 系统自动关闭专用编号，用户动作仍要求真实正数用户ID。 */
    private static final long SYSTEM_OPERATOR_ID = 0L;

    private final OrderMapper orderMapper;
    private final OrderItemMapper  orderItemMapper;
    private final ProductInventoryMapper productInventoryMapper;
    private final OrderOperationLogService operationLogService;
    /** 无关闭资格返回false；仅本次状态转换成功后恢复库存并记录CLOSE，失败向外抛出。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean closeExpiredOrder(Long orderId){
        if(orderId == null || orderId <= 0){
            throw new BusinessException(ErrorCode.REQUEST_INVALID);
        }
        LocalDateTime closeTime = LocalDateTime.now().withNano(0);
        int affectsRows = markOrderClosed(orderId, closeTime);
        if(affectsRows == 0){
            return false;
        } else if (affectsRows == 1) {
            // 仅本次成功转换状态时注册回调，记录后续关闭事务实际提交或回滚的结果。
            LogSupport.transactionOutcome(log, "order_close", orderId);
            //关闭订单 下一步处理库存 按商品ID排序
            List<OrderItemDo> orderItems = orderItemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, orderId).orderByAsc(OrderItemDo::getProductId));
            if (orderItems.isEmpty()) {
                throw new IllegalStateException("订单明细不存在，无法恢复库存");
            }
            //逐个商品加库存
            for (OrderItemDo item : orderItems){
                if (item.getProductId() == null || item.getProductId() <= 0 || item.getQuantity() == null || item.getQuantity() <= 0) {
                    throw new IllegalStateException("订单明细数据异常，无法恢复库存");
                }
                int restoredRows = productInventoryMapper.update(null, Wrappers.<ProductInventoryDo>lambdaUpdate()
                        .eq(ProductInventoryDo::getProductId, item.getProductId())
                        .setIncrBy(ProductInventoryDo::getStock, item.getQuantity()));
                if (restoredRows != 1) {
                    log.error("event=inventory_restore_failed orderId={} productId={} affectedRows={}", orderId, item.getProductId(), restoredRows);
                    // 抛出异常，撤销关闭状态和本事务前面已经恢复的全部库存。
                    throw new BusinessException(ErrorCode.PRODUCT_INVENTORY_MISSING);
                }
            }
            // 只在本次状态转换及库存恢复全部成功后记流水
            operationLogService.recordOperation(orderId, SYSTEM_OPERATOR_ID, OrderOperationAction.CLOSE, closeTime);
        }else {
            throw new IllegalStateException("订单关闭状态更新影响行数异常");
        }
        return true;


    }

    //条件更新订单状态
    private int markOrderClosed(Long orderId, LocalDateTime closeTime){
        return orderMapper.update(null,Wrappers.<OrderDo>lambdaUpdate()
                .eq(OrderDo::getId,orderId)
                .eq(OrderDo::getStatus, OrderStatus.PENDING_PAYMENT)
                .le(OrderDo::getExpiresAt,closeTime)
                .set(OrderDo::getStatus,OrderStatus.CLOSED)
                .set(OrderDo::getUpdatedAt,closeTime)
        );
    }
}
