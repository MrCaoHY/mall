package com.chy.mall;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.service.impl.OrderCloseScanService;
import com.chy.mall.service.impl.OrderCloseTransactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 无效截止时间必须在访问数据库前拒绝。 */
@ExtendWith(MockitoExtension.class)
class OrderCloseScanServiceTest {
    @Mock private OrderMapper orderMapper;
    @Mock private OrderCloseTransactionService closeTransactionService;
    @InjectMocks private OrderCloseScanService service;

    @Test
    void nullScanTimeIsRejectedBeforeDatabaseAccess() {
        assertThrows(IllegalArgumentException.class, () -> service.findExpiredOrderIds(null));
        verifyNoInteractions(orderMapper, closeTransactionService);
    }

    /** 一次处理四单，验证返回值、失败后继续以及日志不会携带异常原始消息。 */
    @Test
    void batchCountsAndContinuesAfterFailure() {
        OrderCloseScanService batch = spy(service);
        LocalDateTime expiresAt = LocalDateTime.now().withNano(0).minusMinutes(1);
        doReturn(List.of(candidate(101L, expiresAt), candidate(102L, expiresAt),
                candidate(103L, expiresAt), candidate(104L, expiresAt)))
                .when(batch).findExpiredOrderCandidates(any(LocalDateTime.class), isNull(), isNull());
        when(closeTransactionService.closeExpiredOrder(101L)).thenReturn(true);
        when(closeTransactionService.closeExpiredOrder(102L)).thenReturn(false);
        when(closeTransactionService.closeExpiredOrder(103L)).thenThrow(new IllegalStateException("private-test-marker"));
        when(closeTransactionService.closeExpiredOrder(104L)).thenReturn(true);
        Logger logger = (Logger) LoggerFactory.getLogger(OrderCloseScanService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertEquals(2, batch.closeExpiredOrdersBatch());
            verify(closeTransactionService).closeExpiredOrder(104L);
            assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage()
                    .contains("total=4 closed=2 skipped=1 failed=1")));
            ILoggingEvent failure = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("event=order_close_scan_item_failed"))
                    .findFirst().orElseThrow();
            assertTrue(failure.getFormattedMessage().contains("orderId=103"));
            assertNotNull(failure.getThrowableProxy());
            assertFalse(failure.getThrowableProxy().getMessage().contains("private-test-marker"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** 游标必须成对提供；调用错误不能被误当成已经扫描到末尾。 */
    @Test
    void invalidCursorIsRejectedBeforeDatabaseAccess() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 10, 29, 12, 0);
        assertThrows(IllegalArgumentException.class, () -> service.findExpiredOrderCandidates(null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.findExpiredOrderCandidates(cutoff, cutoff, null));
        assertThrows(IllegalArgumentException.class, () -> service.findExpiredOrderCandidates(cutoff, null, 1L));
        assertThrows(IllegalArgumentException.class, () -> service.findExpiredOrderCandidates(cutoff, cutoff, 0L));
        assertThrows(IllegalArgumentException.class, () -> service.findExpiredOrderCandidates(cutoff, cutoff, -1L));
        verifyNoInteractions(orderMapper, closeTransactionService);
    }

    /** 整批失败也推进；本轮截止固定，空批回卷后下轮才重新尝试。 */
    @Test
    void batchKeepsCutoffAndRetriesFailureAfterWrap() {
        OrderCloseScanService batch = spy(service);
        LocalDateTime expiresAt = LocalDateTime.now().withNano(0).minusMinutes(1);
        AtomicReference<LocalDateTime> cutoff = new AtomicReference<>();
        AtomicInteger queries = new AtomicInteger();
        doAnswer(invocation -> {
            LocalDateTime currentCutoff = invocation.getArgument(0);
            LocalDateTime previousTime = invocation.getArgument(1);
            Long previousId = invocation.getArgument(2);
            int query = queries.getAndIncrement();
            if (query == 0) {
                cutoff.set(currentCutoff);
                assertNull(previousTime);
                assertNull(previousId);
                return List.of(candidate(201L, expiresAt));
            }
            if (query == 1 || query == 2) {
                assertEquals(cutoff.get(), currentCutoff);
                assertEquals(expiresAt, previousTime);
                assertEquals(query == 1 ? 201L : 202L, previousId);
                return query == 1 ? List.of(candidate(202L, expiresAt)) : List.of();
            }
            assertEquals(3, query, "空批内不能再次查询首页");
            assertEquals(cutoff.get().plusSeconds(10), currentCutoff);
            assertNull(previousTime, "新周期应从头开始");
            assertNull(previousId);
            return List.of(candidate(201L, expiresAt));
        }).when(batch).findExpiredOrderCandidates(any(LocalDateTime.class), nullable(LocalDateTime.class), nullable(Long.class));
        when(closeTransactionService.closeExpiredOrder(201L))
                .thenThrow(new IllegalStateException("controlled-failure")).thenReturn(true);
        when(closeTransactionService.closeExpiredOrder(202L)).thenReturn(true);
        // 控制时间跨过10秒，无需实际等待，固定截止不能靠同秒调用侥幸通过。
        LocalDateTime firstCycle = expiresAt.plusMinutes(1);
        LocalDateTime nextCycle = firstCycle.plusSeconds(10);
        try (var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(LocalDateTime::now).thenReturn(firstCycle, nextCycle);
            clock.clearInvocations(); // 配置桩时的调用不计入业务执行次数。
            assertEquals(0, batch.closeExpiredOrdersBatch());
            assertEquals(firstCycle, cutoff.get());
            assertEquals(1, batch.closeExpiredOrdersBatch());
            assertEquals(0, batch.closeExpiredOrdersBatch());
            assertEquals(3, queries.get(), "空批只进行一次查询");
            assertEquals(1, batch.closeExpiredOrdersBatch());
            assertEquals(4, queries.get());
            clock.verify(LocalDateTime::now, times(2));
        }
        verify(closeTransactionService, times(2)).closeExpiredOrder(201L);
        verify(closeTransactionService).closeExpiredOrder(202L);
        verifyNoInteractions(orderMapper);
    }

    /** 候选查询失败不移动游标；下次仍从上批位置重试。 */
    @Test
    void queryFailureKeepsPreviousCursor() {
        OrderCloseScanService batch = spy(service);
        LocalDateTime expiresAt = LocalDateTime.now().withNano(0).minusMinutes(1);
        AtomicReference<LocalDateTime> cutoff = new AtomicReference<>();
        AtomicInteger queries = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("controlled-query-failure");
        doAnswer(invocation -> {
            int query = queries.getAndIncrement();
            if (query == 0) {
                cutoff.set(invocation.getArgument(0));
                assertNull(invocation.getArgument(1));
                assertNull(invocation.getArgument(2));
                return List.of(candidate(301L, expiresAt));
            }
            assertEquals(cutoff.get(), invocation.getArgument(0));
            assertEquals(expiresAt, invocation.getArgument(1));
            assertEquals(301L, invocation.getArgument(2, Long.class));
            if (query == 1) { throw failure; }
            assertEquals(2, query);
            return List.of(candidate(302L, expiresAt));
        }).when(batch).findExpiredOrderCandidates(any(LocalDateTime.class), nullable(LocalDateTime.class), nullable(Long.class));
        when(closeTransactionService.closeExpiredOrder(301L)).thenReturn(true);
        when(closeTransactionService.closeExpiredOrder(302L)).thenReturn(true);
        assertEquals(1, batch.closeExpiredOrdersBatch());
        assertSame(failure, assertThrows(IllegalStateException.class, batch::closeExpiredOrdersBatch));
        assertEquals(1, batch.closeExpiredOrdersBatch());
        verify(closeTransactionService).closeExpiredOrder(302L);
        verifyNoInteractions(orderMapper);
    }

    private static OrderDo candidate(Long id, LocalDateTime expiresAt) {
        OrderDo order = new OrderDo();
        order.setId(id);
        order.setExpiresAt(expiresAt);
        return order;
    }
}
