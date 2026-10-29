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
    public int closeExpiredOrdersBatch() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        List<Long> expiredOrderIds = findExpiredOrderIds(now);
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
        log.info("event=order_close_scan finished total={} closed={} skipped={} failed={}", expiredOrderIds.size(), closed, skipped, failed);
        return closed;
    }
}
