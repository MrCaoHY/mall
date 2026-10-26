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
import com.chy.mall.service.impl.OrderCloseTransactionService;
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
    @Autowired private OrderCloseTransactionService closeTransactionService;
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

    /** 真实MySQL验证首次关闭与重复触发；测试本身无事务，直接回查Service提交后的结果。 */
    @Test
    void expiredOrderClosesAndRestoresStockExactlyOnce() throws Exception {
        assertTrue(AopUtils.isAopProxy(closeTransactionService), "必须经过Spring事务代理关闭订单");
        var dataSource = jdbc.getDataSource();
        assertNotNull(dataSource);
        try (var connection = dataSource.getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName(), "本用例要求真实MySQL");
            System.out.println("关闭验收数据库：MySQL，schema=" + connection.getCatalog());
        }
        AtomicReference<Long> testedOrderId = new AtomicReference<>();
        AtomicReference<Long> testedProductId = new AtomicReference<>();
        withFixture(fixture -> {
            testedProductId.set(fixture.productId());
            OrderVo created = orderService.create(BUYER_ID, request(fixture));
            Long orderId = created.getId();
            testedOrderId.set(orderId);
            assertEquals(OrderStatus.PENDING_PAYMENT, created.getStatus());
            assertStock(fixture, 3);

            // 只调整本次专用订单的历史时间，保留30分钟支付期限，无需等待或修改生产超时配置。
            LocalDateTime historicalCreatedAt = prepareExpiredOrder(orderId);
            LocalDateTime historicalExpiresAt = historicalCreatedAt.plusMinutes(30);
            // 准备事务已经结束，后续结果不能被测试自身的事务或快照掩盖。
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            OrderDo beforeClose = orderMapper.selectById(orderId);
            assertEquals(historicalCreatedAt, beforeClose.getCreatedAt());
            assertEquals(historicalExpiresAt, beforeClose.getExpiresAt());
            assertTrue(beforeClose.getExpiresAt().isBefore(LocalDateTime.now()));
            List<OrderItemDo> originalItems = itemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, orderId).orderByAsc(OrderItemDo::getId));
            assertEquals(1, originalItems.size());
            assertEquals(1L, count("order_operation_logs", orderId));

            LocalDateTime earliestCloseTime = LocalDateTime.now().withNano(0);
            assertTrue(closeTransactionService.closeExpiredOrder(orderId));
            LocalDateTime latestCloseTime = LocalDateTime.now().withNano(0);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            OrderDo closed = orderMapper.selectById(orderId);
            assertEquals(OrderStatus.CLOSED, closed.getStatus());
            assertNull(closed.getPaidAt());
            assertNull(closed.getCancelledAt());
            assertEquals(beforeClose.getOrderNo(), closed.getOrderNo());
            assertEquals(beforeClose.getBuyerId(), closed.getBuyerId());
            assertEquals(beforeClose.getTotalAmount(), closed.getTotalAmount());
            assertEquals(beforeClose.getCreatedAt(), closed.getCreatedAt());
            assertEquals(beforeClose.getExpiresAt(), closed.getExpiresAt());
            assertNotNull(closed.getUpdatedAt());
            assertFalse(closed.getUpdatedAt().isBefore(earliestCloseTime));
            assertFalse(closed.getUpdatedAt().isAfter(latestCloseTime));
            assertStock(fixture, 5);

            List<OrderOperationLogVo> operations = orderService.listOperations(BUYER_ID, orderId);
            assertEquals(2, operations.size());
            assertRecord(operations.getFirst(), orderId, OrderOperationAction.CREATE, null,
                    OrderStatus.PENDING_PAYMENT, historicalCreatedAt);
            // 原assertRecord固定断言买家身份，系统关闭在这里单独核对操作者0。
            OrderOperationLogVo closeRecord = operations.get(1);
            assertNotNull(closeRecord.getId());
            assertEquals(orderId, closeRecord.getOrderId());
            assertEquals(0L, closeRecord.getOperatorId());
            assertEquals(OrderOperationAction.CLOSE, closeRecord.getAction());
            assertEquals(OrderStatus.PENDING_PAYMENT, closeRecord.getFromStatus());
            assertEquals(OrderStatus.CLOSED, closeRecord.getToStatus());
            assertEquals(closed.getUpdatedAt(), closeRecord.getCreatedAt());

            assertFalse(closeTransactionService.closeExpiredOrder(orderId));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(closed, orderMapper.selectById(orderId), "重复关闭不能改写订单状态或时间");
            assertStock(fixture, 5);
            assertEquals(operations, orderService.listOperations(BUYER_ID, orderId), "重复关闭不能新增流水");
            assertEquals(originalItems, itemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, orderId).orderByAsc(OrderItemDo::getId)), "关闭不改变订单明细快照");
        });
        // withFixture清理事务已经提交，只回查本项主键，确认没有遗留专用数据。
        assertNull(orderMapper.selectById(testedOrderId.get()));
        assertEquals(0L, count("order_items", testedOrderId.get()));
        assertEquals(0L, count("order_operation_logs", testedOrderId.get()));
        assertNull(inventoryMapper.selectById(testedProductId.get()));
        assertNull(productMapper.selectById(testedProductId.get()));
        System.out.println("关闭验收通过：首次true/重复false，库存3→5→5，CREATE/CLOSE各一条，专用数据清理已确认");
    }

    /** 未到期的待支付订单不能被提前关闭。 */
    @Test
    void unexpiredPendingOrderIsSkippedWithoutChanges() throws Exception {
        var dataSource = jdbc.getDataSource();
        assertNotNull(dataSource);
        try (var connection = dataSource.getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            System.out.println("Skip checks use real MySQL, schema=" + connection.getCatalog());
        }
        withFixture(fixture -> {
            OrderVo created = orderService.create(BUYER_ID, request(fixture));
            assertTrue(orderMapper.selectById(created.getId()).getExpiresAt().isAfter(LocalDateTime.now()));
            assertCloseSkippedWithoutChanges(fixture, created.getId(), OrderStatus.PENDING_PAYMENT,
                    3, List.of(OrderOperationAction.CREATE));
        });
    }

    /** 已支付且原支付期限已过去，仍必须保留PAID，不能恢复库存或追加CLOSE。 */
    @Test
    void paidOrderPastDeadlineIsSkippedWithoutChanges() throws Exception {
        withFixture(fixture -> {
            Long orderId = orderService.create(BUYER_ID, request(fixture)).getId();
            assertEquals(OrderStatus.PAID, orderService.pay(BUYER_ID, orderId).getStatus());
            LocalDateTime historicalCreatedAt = LocalDateTime.now().withNano(0).minusMinutes(31);
            LocalDateTime historicalPaidAt = historicalCreatedAt.plusMinutes(5);
            LocalDateTime historicalExpiresAt = historicalCreatedAt.plusMinutes(30);
            // 只把本次已成功支付的专用订单准备成历史数据，保持支付发生于期限内。
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertEquals(1, orderMapper.update(null, Wrappers.<OrderDo>lambdaUpdate()
                        .eq(OrderDo::getId, orderId).eq(OrderDo::getBuyerId, BUYER_ID)
                        .eq(OrderDo::getStatus, OrderStatus.PAID)
                        .set(OrderDo::getCreatedAt, historicalCreatedAt)
                        .set(OrderDo::getExpiresAt, historicalExpiresAt)
                        .set(OrderDo::getPaidAt, historicalPaidAt)
                        .set(OrderDo::getUpdatedAt, historicalPaidAt)));
                assertEquals(1, itemMapper.update(null, Wrappers.<OrderItemDo>lambdaUpdate()
                        .eq(OrderItemDo::getOrderId, orderId)
                        .set(OrderItemDo::getCreatedAt, historicalCreatedAt)
                        .set(OrderItemDo::getUpdatedAt, historicalCreatedAt)));
                assertEquals(1, logMapper.update(null, Wrappers.<OrderOperationLogDo>lambdaUpdate()
                        .eq(OrderOperationLogDo::getOrderId, orderId)
                        .eq(OrderOperationLogDo::getAction, OrderOperationAction.CREATE)
                        .set(OrderOperationLogDo::getCreatedAt, historicalCreatedAt)));
                assertEquals(1, logMapper.update(null, Wrappers.<OrderOperationLogDo>lambdaUpdate()
                        .eq(OrderOperationLogDo::getOrderId, orderId)
                        .eq(OrderOperationLogDo::getAction, OrderOperationAction.PAY)
                        .set(OrderOperationLogDo::getCreatedAt, historicalPaidAt)));
            });
            OrderDo paid = orderMapper.selectById(orderId);
            assertTrue(paid.getExpiresAt().isBefore(LocalDateTime.now()));
            assertTrue(paid.getPaidAt().isBefore(paid.getExpiresAt()));
            assertCloseSkippedWithoutChanges(fixture, orderId, OrderStatus.PAID,
                    3, List.of(OrderOperationAction.CREATE, OrderOperationAction.PAY));
        });
    }

    /** 已取消的过期订单库存已经恢复，关闭扫描不能再次恢复。 */
    @Test
    void cancelledOrderPastDeadlineIsSkippedWithoutChanges() throws Exception {
        withFixture(fixture -> {
            Long orderId = orderService.create(BUYER_ID, request(fixture)).getId();
            prepareExpiredOrder(orderId);
            assertEquals(OrderStatus.CANCELLED, orderService.cancel(BUYER_ID, orderId).getStatus());
            assertTrue(orderMapper.selectById(orderId).getExpiresAt().isBefore(LocalDateTime.now()));
            assertCloseSkippedWithoutChanges(fixture, orderId, OrderStatus.CANCELLED,
                    5, List.of(OrderOperationAction.CREATE, OrderOperationAction.CANCEL));
        });
    }

    /** 不存在的合法主键直接跳过，不创建幽灵订单或操作记录。 */
    @Test
    void missingOrderIsSkippedWithoutCreatingData() {
        Long missingId = Long.MAX_VALUE;
        assertTrue(AopUtils.isAopProxy(closeTransactionService));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertNull(orderMapper.selectById(missingId));
        assertEquals(0L, count("order_items", missingId));
        assertEquals(0L, count("order_operation_logs", missingId));
        assertFalse(closeTransactionService.closeExpiredOrder(missingId));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertNull(orderMapper.selectById(missingId));
        assertEquals(0L, count("order_items", missingId));
        assertEquals(0L, count("order_operation_logs", missingId));
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

    /** CLOSE真实落库后抛异常，验证整个关闭事务撤销状态、已恢复库存和新流水。 */
    @Test
    void failureAfterCloseRecordInsertRollsBackStatusInventoryAndRecord() throws Exception {
        assertTrue(AopUtils.isAopProxy(closeTransactionService));
        AtomicReference<Long> testedOrderId = new AtomicReference<>();
        AtomicReference<Long> testedProductId = new AtomicReference<>();
        withFixture(fixture -> {
            testedProductId.set(fixture.productId());
            OrderVo created = orderService.create(BUYER_ID, request(fixture));
            Long orderId = created.getId();
            testedOrderId.set(orderId);
            prepareExpiredOrder(orderId);
            OrderDo beforeClose = orderMapper.selectById(orderId);
            assertEquals(OrderStatus.PENDING_PAYMENT, beforeClose.getStatus());
            assertTrue(beforeClose.getExpiresAt().isBefore(LocalDateTime.now()));
            assertStock(fixture, 3);
            List<OrderItemDo> originalItems = itemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, orderId).orderByAsc(OrderItemDo::getId));
            List<OrderOperationLogVo> originalOperations = orderService.listOperations(BUYER_ID, orderId);
            assertEquals(1, originalOperations.size());
            assertEquals(OrderOperationAction.CREATE, originalOperations.getFirst().getAction());

            IllegalStateException failure = new IllegalStateException("测试注入CLOSE流水保存后失败");
            AtomicReference<Long> attemptedId = failAfterRealRecordInsert(fixture, OrderOperationAction.CLOSE, failure);
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> closeTransactionService.closeExpiredOrder(orderId)));
            assertEquals(orderId, attemptedId.get(), "必须确实执行到本订单CLOSE真实插入后的故障点");
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());

            // 回滚已经完成，在事务外直接读取真实表；不能只验证Mock调用或内存中的实体。
            assertEquals(beforeClose, orderMapper.selectById(orderId), "关闭状态和所有时间字段必须回滚");
            assertStock(fixture, 3);
            assertEquals(originalOperations, orderService.listOperations(BUYER_ID, orderId), "只保留原CREATE流水");
            assertEquals(1L, count("order_operation_logs", orderId));
            assertEquals(originalItems, itemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, orderId).orderByAsc(OrderItemDo::getId)));
        });
        assertNull(orderMapper.selectById(testedOrderId.get()));
        assertEquals(0L, count("order_items", testedOrderId.get()));
        assertEquals(0L, count("order_operation_logs", testedOrderId.get()));
        assertNull(inventoryMapper.selectById(testedProductId.get()));
        assertNull(productMapper.selectById(testedProductId.get()));
        System.out.println("CLOSE rollback verified: original order/stock/CREATE restored; fixture cleanup verified");
    }

    @Test
    void recordingWithoutBusinessTransactionIsRejected() {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertThrows(IllegalTransactionStateException.class, () -> operationLogService.recordOperation(
                Long.MAX_VALUE, BUYER_ID, OrderOperationAction.CREATE, LocalDateTime.now().withNano(0)));
    }

    /** 在事务外比较关闭前后的完整真实快照，覆盖所有安全跳过场景的无副作用契约。 */
    private void assertCloseSkippedWithoutChanges(Fixture fixture, Long orderId, OrderStatus expectedStatus,
                                                  int expectedStock, List<OrderOperationAction> expectedActions) {
        assertTrue(AopUtils.isAopProxy(closeTransactionService));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        OrderDo before = orderMapper.selectById(orderId);
        assertEquals(expectedStatus, before.getStatus());
        assertStock(fixture, expectedStock);
        List<OrderItemDo> originalItems = itemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                .eq(OrderItemDo::getOrderId, orderId).orderByAsc(OrderItemDo::getId));
        List<OrderOperationLogVo> originalOperations = orderService.listOperations(BUYER_ID, orderId);
        assertEquals(expectedActions, originalOperations.stream().map(OrderOperationLogVo::getAction).toList());

        assertFalse(closeTransactionService.closeExpiredOrder(orderId));

        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(before, orderMapper.selectById(orderId), "跳过不能改写状态或任何业务时间");
        assertStock(fixture, expectedStock);
        assertEquals(originalOperations, orderService.listOperations(BUYER_ID, orderId), "跳过不能新增或修改流水");
        assertEquals(originalItems, itemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                .eq(OrderItemDo::getOrderId, orderId).orderByAsc(OrderItemDo::getId)));
    }

    /** 两个关闭场景共用同一份已提交的过期数据准备，范围只限本次随机订单。 */
    private LocalDateTime prepareExpiredOrder(Long orderId) {
        LocalDateTime historicalCreatedAt = LocalDateTime.now().withNano(0).minusMinutes(31);
        LocalDateTime historicalExpiresAt = historicalCreatedAt.plusMinutes(30);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertEquals(1, orderMapper.update(null, Wrappers.<OrderDo>lambdaUpdate()
                    .eq(OrderDo::getId, orderId).eq(OrderDo::getBuyerId, BUYER_ID)
                    .eq(OrderDo::getStatus, OrderStatus.PENDING_PAYMENT)
                    .set(OrderDo::getCreatedAt, historicalCreatedAt)
                    .set(OrderDo::getUpdatedAt, historicalCreatedAt)
                    .set(OrderDo::getExpiresAt, historicalExpiresAt)));
            assertEquals(1, itemMapper.update(null, Wrappers.<OrderItemDo>lambdaUpdate()
                    .eq(OrderItemDo::getOrderId, orderId)
                    .set(OrderItemDo::getCreatedAt, historicalCreatedAt)
                    .set(OrderItemDo::getUpdatedAt, historicalCreatedAt)));
            assertEquals(1, logMapper.update(null, Wrappers.<OrderOperationLogDo>lambdaUpdate()
                    .eq(OrderOperationLogDo::getOrderId, orderId)
                    .eq(OrderOperationLogDo::getAction, OrderOperationAction.CREATE)
                    .set(OrderOperationLogDo::getCreatedAt, historicalCreatedAt)));
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        return historicalCreatedAt;
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
                // 取消和超时关闭都已恢复库存；创建和支付的事务内库存仍为下单后的3。
                boolean stockRestored = failingAction == OrderOperationAction.CANCEL
                        || failingAction == OrderOperationAction.CLOSE;
                assertStock(fixture, stockRestored ? 5 : 3);
                if (failingAction == OrderOperationAction.CLOSE) {
                    assertEquals(0L, invocation.getArgument(1, Long.class));
                    assertEquals(2L, count("order_operation_logs", orderId), "原CREATE与新CLOSE此时确实都存在");
                }
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
