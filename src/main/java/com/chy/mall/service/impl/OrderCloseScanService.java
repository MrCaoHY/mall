package com.chy.mall.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.enums.OrderStatus;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.util.LogSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCloseScanService {
    private final OrderMapper orderMapper;
    private final OrderCloseTransactionService orderCloseTransactionService;
    //本轮扫描截止时间
    private LocalDateTime cycleCutoff;
    //上一批最后一条订单到期时间
    private LocalDateTime lastExpiresAt;
    //上一批最后一条订单id
    private Long lastId;

    /**
     * 查找一批候选订单；是否真正关闭由后续单笔事务再次判断。
     */
    public List<Long> findExpiredOrderIds(LocalDateTime scanTime) {
        if (scanTime == null) {
            throw new IllegalArgumentException("扫描截止时间不能为空");
        }


        // 只查询候选主键；相同到期时间使用主键排序，保证每批结果顺序稳定。
        Page<OrderDo> page = orderMapper.selectPage(new Page<>(1, 100, false),
                Wrappers.<OrderDo>lambdaQuery()
                        .select(OrderDo::getId)
                        .eq(OrderDo::getStatus, OrderStatus.PENDING_PAYMENT)
                        .le(OrderDo::getExpiresAt, scanTime)
                        .orderByAsc(OrderDo::getExpiresAt, OrderDo::getId));
        return page.getRecords().stream()
                .map(OrderDo::getId)
                .toList();
    }

    /**
     * 批量扫描并关闭已到期未支付订单。
     * 使用 NEVER 传播机制防止外层大事务，每笔订单内部由独立事务提交或回滚。
     */
    @Transactional(propagation = Propagation.NEVER)
    public synchronized int closeExpiredOrdersBatch() {
        if (cycleCutoff == null) {
            cycleCutoff = LocalDateTime.now().withNano(0);
        }
        List<OrderDo> expiredOrders = findExpiredOrderCandidates(cycleCutoff, lastExpiresAt, lastId);

        List<Long> expiredOrderIds = expiredOrders.stream().map(OrderDo::getId).toList();
        if (expiredOrderIds.isEmpty()) {
            cycleCutoff = null;
            lastExpiresAt = null;
            lastId = null;
            return 0;
        }
        int closed = 0;
        int skipped = 0;
        int failed = 0;
        for (Long orderId : expiredOrderIds) {
            try {
                // 逐单调用独立的关闭事务（跨 Bean 调用，单单隔离）
                boolean success = orderCloseTransactionService.closeExpiredOrder(orderId);
                if (success) {
                    closed++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                failed++;
                // 数据库异常可能携带敏感实参，只记录脱敏堆栈。
                log.error("event=order_close_scan_item_failed orderId={}", orderId, LogSupport.safeStackTrace(e));
            }
        }
        lastExpiresAt = expiredOrders.get(expiredOrders.size() - 1).getExpiresAt();
        lastId = expiredOrderIds.get(expiredOrderIds.size() - 1);
        log.info("event=order_close_scan finished total={} closed={} skipped={} failed={}", expiredOrderIds.size(), closed, skipped, failed);
        return closed;
    }

    /**
     * 查询游标之后的一批候选；两项游标都为空时查询首页。
     * 只返回id和expiresAt，供调用方继续扫描，不作为完整订单实体使用。
     */
    public List<OrderDo> findExpiredOrderCandidates(LocalDateTime scanTime, LocalDateTime lastExpiresAt, Long lastId) {
        if (scanTime == null) {
            throw new IllegalArgumentException("扫描截止时间不能为空");
        }
        if ((lastExpiresAt == null) != (lastId == null) || (lastId != null && lastId <= 0)) {
            throw new IllegalArgumentException("游标时间和正数订单ID必须同时提供");
        }
        var query = Wrappers.<OrderDo>lambdaQuery()
                .select(OrderDo::getId, OrderDo::getExpiresAt)
                .eq(OrderDo::getStatus, OrderStatus.PENDING_PAYMENT)
                .le(OrderDo::getExpiresAt, scanTime);
        if (lastId != null) {
            // OR必须嵌套在AND分组内，避免绕过待支付和截止时间条件。
            query.and(cursor -> cursor.gt(OrderDo::getExpiresAt, lastExpiresAt)
                    .or(sameTime -> sameTime.eq(OrderDo::getExpiresAt, lastExpiresAt)
                            .gt(OrderDo::getId, lastId)));
        }
        return orderMapper.selectList(query.orderByAsc(OrderDo::getExpiresAt, OrderDo::getId).last("LIMIT 100"));
    }
}
