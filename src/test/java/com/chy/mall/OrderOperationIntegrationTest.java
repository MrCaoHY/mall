package com.chy.mall;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.dto.CreateOrderItemRequest;
import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.entity.*;
import com.chy.mall.enums.*;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.*;
import com.chy.mall.security.AuthenticatedUser;
import com.chy.mall.service.OrderService;
import com.chy.mall.service.impl.OrderOperationLogService;
import com.chy.mall.vo.OrderOperationLogVo;
import com.chy.mall.vo.OrderVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 显式开关的真实MySQL验收：测试本身不加事务，分别观察业务提交和回滚后的数据库。
 * 随机专用商品独立提交；只清理本次fixture，失败保留现场，不处理既有订单或库存。
 * 运行：mvnw.cmd "-Dtest=OrderOperationIntegrationTest" "-Dmall.order.operations.enabled=true" test
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "mall.order.operations.enabled", matches = "true")
class OrderOperationIntegrationTest {
    private static final Long BUYER_ID = 1L;

    @Autowired private OrderService orderService;
    @Autowired private ProductMapper productMapper;
    @Autowired private ProductInventoryMapper inventoryMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private OrderItemMapper itemMapper;
    @Autowired private OrderOperationLogMapper logMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mvc;

    // Spy具体业务类，Mapper全部真实；callRealMethod用于具体Java方法，不用于JDK Mapper接口。
    @MockitoSpyBean
    private OrderOperationLogService operationLogService;

    @DynamicPropertySource
    static void testJwtSecret(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String secret = Base64.getEncoder().encodeToString(key);
        registry.add("jwt.secret", () -> secret);
    }

    @Test
    void createAndPayRetriesProduceExactlyTwoRecordsAndCanBeReadThroughMvc() throws Exception {
        withFixture(fixture -> {
            OrderVo created = orderService.create(BUYER_ID, request(fixture));
            assertEquals(created.getId(), orderService.create(BUYER_ID, request(fixture)).getId());
            assertEquals(1, orderService.listOperations(BUYER_ID, created.getId()).size());
            OrderVo paid = orderService.pay(BUYER_ID, created.getId());
            LocalDateTime firstPaidAt = orderMapper.selectById(created.getId()).getPaidAt();
            assertEquals(OrderStatus.PAID, orderService.pay(BUYER_ID, created.getId()).getStatus());
            assertEquals(firstPaidAt, orderMapper.selectById(created.getId()).getPaidAt());
            List<OrderOperationLogVo> operations = orderService.listOperations(BUYER_ID, paid.getId());
            assertEquals(2, operations.size());
            assertRecord(operations.get(0), created.getId(), OrderOperationAction.CREATE, null,
                    OrderStatus.PENDING_PAYMENT, created.getCreatedAt());
            assertRecord(operations.get(1), created.getId(), OrderOperationAction.PAY,
                    OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, firstPaidAt);
            assertStock(fixture, 3);
            BusinessException foreignOrder = assertThrows(BusinessException.class,
                    () -> orderService.listOperations(BUYER_ID + 1, created.getId()));
            assertEquals(ErrorCode.ORDER_NOT_FOUND, foreignOrder.getErrorCode());

            // 功能链使用模拟认证主体，不把此MockMvc检查称为真实JWT登录验收。
            var principal = UsernamePasswordAuthenticationToken.authenticated(
                    new AuthenticatedUser(BUYER_ID, "operation-test", UserRole.USER), null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));
            mvc.perform(get("/orders/{orderId}/operations", created.getId())
                            .with(authentication(principal)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].action").value("CREATE"))
                    .andExpect(jsonPath("$.data[0].fromStatus").doesNotExist())
                    .andExpect(jsonPath("$.data[1].action").value("PAY"))
                    .andExpect(jsonPath("$.data[1].toStatus").value("PAID"));
        });
    }

    @Test
    void cancelRetryProducesOneCancelRecordAndRestoresStockOnlyOnce() throws Exception {
        withFixture(fixture -> {
            OrderVo created = orderService.create(BUYER_ID, request(fixture));
            assertEquals(OrderStatus.CANCELLED, orderService.cancel(BUYER_ID, created.getId()).getStatus());
            assertEquals(OrderStatus.CANCELLED, orderService.cancel(BUYER_ID, created.getId()).getStatus());
            List<OrderOperationLogVo> operations = orderService.listOperations(BUYER_ID, created.getId());
            assertEquals(2, operations.size());
            assertRecord(operations.get(1), created.getId(), OrderOperationAction.CANCEL,
                    OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED,
                    orderMapper.selectById(created.getId()).getCancelledAt());
            assertStock(fixture, 5);
        });
    }

    @Test
    void failureAfterCreateRecordInsertRollsBackOrderItemsStockAndRecord() throws Exception {
        withFixture(fixture -> {
            IllegalStateException failure = new IllegalStateException("测试注入CREATE流水保存后失败");
            AtomicReference<Long> attemptedId = failAfterRealRecordInsert(fixture, OrderOperationAction.CREATE, failure);
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> orderService.create(BUYER_ID, request(fixture))));
            assertNotNull(attemptedId.get());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertNull(orderMapper.selectById(attemptedId.get()));
            assertEquals(0L, count("order_items", attemptedId.get()));
            assertEquals(0L, count("order_operation_logs", attemptedId.get()));
            assertStock(fixture, 5);
        });
    }

    @Test
    void failureAfterPayRecordInsertRollsBackPaymentAndRecord() throws Exception {
        withFixture(fixture -> {
            OrderVo created = orderService.create(BUYER_ID, request(fixture));
            IllegalStateException failure = new IllegalStateException("测试注入PAY流水保存后失败");
            failAfterRealRecordInsert(fixture, OrderOperationAction.PAY, failure);
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> orderService.pay(BUYER_ID, created.getId())));
            OrderDo saved = orderMapper.selectById(created.getId());
            assertEquals(OrderStatus.PENDING_PAYMENT, saved.getStatus());
            assertNull(saved.getPaidAt());
            assertEquals(1L, count("order_operation_logs", created.getId()));
            assertEquals(OrderOperationAction.CREATE,
                    orderService.listOperations(BUYER_ID, created.getId()).getFirst().getAction());
            assertStock(fixture, 3);
        });
    }

    @Test
    void failureAfterCancelRecordInsertRollsBackStatusInventoryAndRecord() throws Exception {
        withFixture(fixture -> {
            OrderVo created = orderService.create(BUYER_ID, request(fixture));
            IllegalStateException failure = new IllegalStateException("测试注入CANCEL流水保存后失败");
            failAfterRealRecordInsert(fixture, OrderOperationAction.CANCEL, failure);
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> orderService.cancel(BUYER_ID, created.getId())));
            OrderDo saved = orderMapper.selectById(created.getId());
            assertEquals(OrderStatus.PENDING_PAYMENT, saved.getStatus());
            assertNull(saved.getCancelledAt());
            assertEquals(1L, count("order_operation_logs", created.getId()));
            assertStock(fixture, 3);
        });
    }

    @Test
    void recordingWithoutBusinessTransactionIsRejected() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertThrows(IllegalTransactionStateException.class, () -> operationLogService.recordOperation(
                Long.MAX_VALUE, BUYER_ID, OrderOperationAction.CREATE, LocalDateTime.now().withNano(0)));
    }

    /** 在真实INSERT已完成后注入异常，并在同事务中确认中间状态确实存在。 */
    private AtomicReference<Long> failAfterRealRecordInsert(
            Fixture fixture, OrderOperationAction failingAction, IllegalStateException failure) {
        assertTrue(AopUtils.isAopProxy(operationLogService));
        OrderOperationLogService observer = AopTestUtils.getUltimateTargetObject(operationLogService);
        assertTrue(mockingDetails(observer).isMock(), "预期具体Service由Mockito包装");
        AtomicReference<Long> attemptedId = new AtomicReference<>();
        doAnswer(invocation -> {
            // 与接口Mapper不同，这里是具体Service方法，真实方法可以正常调用。
            invocation.callRealMethod();
            OrderOperationAction action = invocation.getArgument(2);
            if (action == failingAction) {
                Long orderId = invocation.getArgument(0);
                attemptedId.set(orderId);
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertNotNull(orderMapper.selectById(orderId));
                assertEquals(failingAction.getToStatus(), orderMapper.selectById(orderId).getStatus());
                assertEquals(1L, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_operation_logs WHERE order_id = ? AND action = ?",
                        Long.class, orderId, failingAction.name()));
                assertEquals(1L, count("order_items", orderId));
                assertStock(fixture, failingAction == OrderOperationAction.CANCEL ? 5 : 3);
                throw failure;
            }
            return null;
        }).when(observer).recordOperation(anyLong(), anyLong(), any(OrderOperationAction.class), any(LocalDateTime.class));
        return attemptedId;
    }

    private long count(String table, Long orderId) {
        // 表名只来自本类常量调用，订单ID使用占位参数，且查询走真实JdbcTemplate。
        if (!List.of("order_items", "order_operation_logs").contains(table)) {
            throw new IllegalArgumentException("测试表名不合法");
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE order_id = ?", Long.class, orderId);
    }

    private void assertStock(Fixture fixture, int stock) {
        assertEquals(stock, inventoryMapper.selectById(fixture.productId()).getStock());
    }

    private static void assertRecord(OrderOperationLogVo record, Long orderId, OrderOperationAction action,
                                     OrderStatus from, OrderStatus to, LocalDateTime time) {
        assertNotNull(record.getId());
        assertEquals(orderId, record.getOrderId());
        assertEquals(BUYER_ID, record.getOperatorId());
        assertEquals(action, record.getAction());
        assertEquals(from, record.getFromStatus());
        assertEquals(to, record.getToStatus());
        assertEquals(time, record.getCreatedAt());
    }

    private static CreateOrderRequest request(Fixture fixture) {
        CreateOrderItemRequest item = new CreateOrderItemRequest();
        item.setProductId(fixture.productId());
        item.setQuantity(2);
        CreateOrderRequest result = new CreateOrderRequest();
        result.setRequestId(fixture.requestId());
        result.setItems(List.of(item));
        return result;
    }

    /** 独立提交专用商品，避免测试自动回滚掩盖真实业务事务。 */
    private void withFixture(Scenario scenario) throws Exception {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        ProductDo product = new ProductDo();
        product.setSku("OP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 28));
        product.setName("订单流水验收专用商品");
        product.setPrice(new BigDecimal("19.90"));
        product.setStatus(ProductStatus.ON_SALE);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertEquals(1, productMapper.insert(product));
            ProductInventoryDo inventory = new ProductInventoryDo();
            inventory.setProductId(product.getId());
            inventory.setStock(5);
            assertEquals(1, inventoryMapper.insert(inventory));
        });
        Fixture fixture = new Fixture(product.getId(), UUID.randomUUID().toString());
        boolean passed = false;
        try {
            scenario.run(fixture);
            passed = true;
        } finally {
            if (passed) {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    List<OrderDo> orders = orderMapper.selectList(Wrappers.<OrderDo>lambdaQuery()
                            .eq(OrderDo::getBuyerId, BUYER_ID).eq(OrderDo::getRequestId, fixture.requestId()));
                    for (OrderDo order : orders) {
                        logMapper.delete(Wrappers.<OrderOperationLogDo>lambdaQuery()
                                .eq(OrderOperationLogDo::getOrderId, order.getId()));
                        itemMapper.delete(Wrappers.<OrderItemDo>lambdaQuery().eq(OrderItemDo::getOrderId, order.getId()));
                        assertEquals(1, orderMapper.deleteById(order.getId()));
                    }
                    assertEquals(1, inventoryMapper.deleteById(fixture.productId()));
                    assertEquals(1, productMapper.deleteById(fixture.productId()));
                });
            } else {
                System.err.printf("流水测试失败，保留专用现场：productId=%s, requestId=%s%n",
                        fixture.productId(), fixture.requestId());
            }
        }
    }

    @FunctionalInterface
    private interface Scenario { void run(Fixture fixture) throws Exception; }
    private record Fixture(Long productId, String requestId) { }
}
