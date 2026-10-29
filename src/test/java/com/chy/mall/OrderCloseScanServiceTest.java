package com.chy.mall;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
        doReturn(List.of(101L, 102L, 103L, 104L)).when(batch).findExpiredOrderIds(any(LocalDateTime.class));
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
}
