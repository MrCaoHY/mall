package com.chy.mall;

import com.chy.mall.entity.OrderOperationLogDo;
import com.chy.mall.enums.OrderOperationAction;
import com.chy.mall.enums.OrderStatus;
import com.chy.mall.mapper.OrderOperationLogMapper;
import com.chy.mall.service.impl.OrderOperationLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 流水组装及影响行数检查；MANDATORY事务约束另由真实Spring测试验证。 */
@ExtendWith(MockitoExtension.class)
class OrderOperationLogServiceTest {
    @Mock private OrderOperationLogMapper mapper;
    @InjectMocks private OrderOperationLogService service;
    @Captor private ArgumentCaptor<OrderOperationLogDo> operationCaptor;

    @Test
    void createRecordKeepsNullPreviousStatusAndTrustedBusinessTime() {
        LocalDateTime time = LocalDateTime.of(2026, 10, 21, 10, 0);
        when(mapper.insert(any(OrderOperationLogDo.class))).thenReturn(1);
        service.recordOperation(9001L, 77L, OrderOperationAction.CREATE, time);
        verify(mapper).insert(operationCaptor.capture());
        OrderOperationLogDo saved = operationCaptor.getValue();
        assertEquals(9001L, saved.getOrderId());
        assertEquals(77L, saved.getOperatorId());
        assertEquals(OrderOperationAction.CREATE, saved.getAction());
        assertNull(saved.getFromStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT, saved.getToStatus());
        assertEquals(time, saved.getCreatedAt());
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void zeroInsertRowsMustFailInsteadOfSilentlyCommittingBusiness() {
        when(mapper.insert(any(OrderOperationLogDo.class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.recordOperation(
                9001L, 77L, OrderOperationAction.PAY, LocalDateTime.now().withNano(0)));
        verify(mapper).insert(any(OrderOperationLogDo.class));
        verifyNoMoreInteractions(mapper);
    }
}
