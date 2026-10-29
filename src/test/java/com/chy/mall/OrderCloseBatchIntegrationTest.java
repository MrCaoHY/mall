package com.chy.mall;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chy.mall.dto.CreateOrderItemRequest;
import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.entity.*;
import com.chy.mall.enums.*;
import com.chy.mall.mapper.*;
import com.chy.mall.service.OrderService;
import com.chy.mall.service.impl.OrderCloseScanService;
import com.chy.mall.service.impl.OrderOperationLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 候选查询定向为测试专用ID；单笔业务、数据库操作和事务均真实。 */
@SpringBootTest
@EnabledIfSystemProperty(named = "mall.order.scan.enabled", matches = "true")
class OrderCloseBatchIntegrationTest {
    private static final long BUYER_ID = 1L;
    @Autowired private OrderCloseScanService scanService;
    @Autowired private OrderService orderService;
    @Autowired private ProductMapper productMapper;
    @Autowired private ProductInventoryMapper inventoryMapper;
    @Autowired private OrderItemMapper itemMapper;
    @Autowired private OrderOperationLogMapper logMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private OrderMapper orderMapper;
    @MockitoSpyBean private OrderOperationLogService operationLogService;

    @DynamicPropertySource
    static void testJwtSecret(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String secret = Base64.getEncoder().encodeToString(key);
        registry.add("jwt.secret", () -> secret);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedOrderRollsBackAndNextOrderCommits() {
        assertTrue(AopUtils.isAopProxy(scanService), "批处理必须通过Spring代理");
        assertTrue(AopUtils.isAopProxy(operationLogService), "流水必须通过Spring代理");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        List<Long> productIds = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        boolean passed = false;
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                for (int i = 0; i < 2; i++) {
                    ProductDo product = new ProductDo();
                    product.setSku("BATCH-" + UUID.randomUUID().toString().replace("-", "").substring(0, 25));
                    product.setName("批处理集成测试专用商品");
                    product.setPrice(new BigDecimal("19.90"));
                    product.setStatus(ProductStatus.ON_SALE);
                    assertEquals(1, productMapper.insert(product));
                    productIds.add(product.getId());
                    ProductInventoryDo inventory = new ProductInventoryDo();
                    inventory.setProductId(product.getId());
                    inventory.setStock(5);
                    assertEquals(1, inventoryMapper.insert(inventory));
                }
            });
            for (Long productId : productIds) {
                CreateOrderItemRequest item = new CreateOrderItemRequest();
                item.setProductId(productId);
                item.setQuantity(2);
                CreateOrderRequest request = new CreateOrderRequest();
                request.setRequestId(UUID.randomUUID().toString());
                request.setItems(List.of(item));
                orderIds.add(orderService.create(BUYER_ID, request).getId());
            }
            LocalDateTime createdAt = LocalDateTime.now().withNano(0).minusMinutes(31);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                for (Long orderId : orderIds) {
                    assertEquals(1, orderMapper.update(null, Wrappers.<OrderDo>lambdaUpdate()
                            .eq(OrderDo::getId, orderId).eq(OrderDo::getStatus, OrderStatus.PENDING_PAYMENT)
                            .set(OrderDo::getCreatedAt, createdAt).set(OrderDo::getUpdatedAt, createdAt)
                            .set(OrderDo::getExpiresAt, createdAt.plusMinutes(30))));
                    assertEquals(1, itemMapper.update(null, Wrappers.<OrderItemDo>lambdaUpdate()
                            .eq(OrderItemDo::getOrderId, orderId)
                            .set(OrderItemDo::getCreatedAt, createdAt).set(OrderItemDo::getUpdatedAt, createdAt)));
                    assertEquals(1, logMapper.update(null, Wrappers.<OrderOperationLogDo>lambdaUpdate()
                            .eq(OrderOperationLogDo::getOrderId, orderId)
                            .eq(OrderOperationLogDo::getAction, OrderOperationAction.CREATE)
                            .set(OrderOperationLogDo::getCreatedAt, createdAt)));
                }
            });
            Long failedId = orderIds.getFirst();
            Long successId = orderIds.get(1);
            OrderDo beforeFailure = orderMapper.selectById(failedId);
            List<OrderItemDo> failedItems = items(failedId);
            List<OrderItemDo> successfulItems = items(successId);
            assertEquals(3, inventoryMapper.selectById(productIds.getFirst()).getStock());
            assertEquals(3, inventoryMapper.selectById(productIds.get(1)).getStock());

            // 只替换全局候选分页查询，禁止批处理触及现有订单；先验证该桩确实生效。
            OrderMapper mapperObserver = mapperObserver(orderMapper);
            doAnswer(invocation -> {
                Page<OrderDo> page = invocation.getArgument(0);
                List<OrderDo> candidates = new ArrayList<>();
                for (Long id : orderIds) {
                    OrderDo candidate = new OrderDo();
                    candidate.setId(id);
                    candidates.add(candidate);
                }
                page.setRecords(candidates);
                return page;
            }).when(mapperObserver).selectPage(any(Page.class), any(Wrapper.class));
            assertEquals(orderIds, scanService.findExpiredOrderIds(LocalDateTime.now()), "定向候选桩必须先生效");
            clearInvocations(mapperObserver);

            OrderOperationLogService logObserver = AopTestUtils.getUltimateTargetObject(operationLogService);
            assertTrue(mockingDetails(logObserver).isMock());
            doAnswer(invocation -> {
                invocation.callRealMethod();
                Long orderId = invocation.getArgument(0);
                OrderOperationAction action = invocation.getArgument(2);
                if (orderId.equals(failedId) && action == OrderOperationAction.CLOSE) {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    assertEquals(OrderStatus.CLOSED, orderMapper.selectById(failedId).getStatus());
                    assertEquals(5, inventoryMapper.selectById(productIds.getFirst()).getStock());
                    assertEquals(2, operations(failedId).size(), "真实CLOSE流水已插入，再注入失败");
                    throw new IllegalStateException("controlled-batch-test-failure");
                }
                return null;
            }).when(logObserver).recordOperation(anyLong(), anyLong(), any(OrderOperationAction.class), any(LocalDateTime.class));

            assertEquals(1, scanService.closeExpiredOrdersBatch());
            verify(mapperObserver, times(1)).selectPage(any(Page.class), any(Wrapper.class));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(beforeFailure, orderMapper.selectById(failedId));
            assertEquals(3, inventoryMapper.selectById(productIds.getFirst()).getStock());
            assertEquals(failedItems, items(failedId));
            assertEquals(List.of(OrderOperationAction.CREATE), operations(failedId).stream().map(OrderOperationLogDo::getAction).toList());
            OrderDo successful = orderMapper.selectById(successId);
            assertEquals(OrderStatus.CLOSED, successful.getStatus());
            assertEquals(5, inventoryMapper.selectById(productIds.get(1)).getStock());
            assertEquals(successfulItems, items(successId));
            List<OrderOperationLogDo> successfulOperations = operations(successId);
            assertEquals(List.of(OrderOperationAction.CREATE, OrderOperationAction.CLOSE),
                    successfulOperations.stream().map(OrderOperationLogDo::getAction).toList());
            assertEquals(0L, successfulOperations.get(1).getOperatorId());
            assertEquals(successful.getUpdatedAt(), successfulOperations.get(1).getCreatedAt());
            passed = true;
        } finally {
            if (passed) {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    for (Long orderId : orderIds) {
                        logMapper.delete(Wrappers.<OrderOperationLogDo>lambdaQuery().eq(OrderOperationLogDo::getOrderId, orderId));
                        itemMapper.delete(Wrappers.<OrderItemDo>lambdaQuery().eq(OrderItemDo::getOrderId, orderId));
                        assertEquals(1, orderMapper.deleteById(orderId));
                    }
                    for (Long productId : productIds) {
                        assertEquals(1, inventoryMapper.deleteById(productId));
                        assertEquals(1, productMapper.deleteById(productId));
                    }
                });
                for (Long orderId : orderIds) {
                    assertNull(orderMapper.selectById(orderId));
                    assertTrue(items(orderId).isEmpty());
                    assertTrue(operations(orderId).isEmpty());
                }
                for (Long productId : productIds) {
                    assertNull(inventoryMapper.selectById(productId));
                    assertNull(productMapper.selectById(productId));
                }
            } else {
                System.err.printf("批处理测试失败，保留专用现场：orderIds=%s, productIds=%s%n", orderIds, productIds);
            }
        }
    }

    /** NEVER必须由Spring代理在进入扫描前拒绝已有事务。 */
    @Test
    @SuppressWarnings("unchecked")
    void existingTransactionIsRejectedBeforeCandidateQuery() {
        assertTrue(AopUtils.isAopProxy(scanService));
        OrderMapper observer = mapperObserver(orderMapper);
        clearInvocations(observer);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertThrows(IllegalTransactionStateException.class, () -> scanService.closeExpiredOrdersBatch());
        });
        verify(observer, never()).selectPage(any(Page.class), any(Wrapper.class));
    }

    private List<OrderItemDo> items(Long orderId) {
        return itemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                .eq(OrderItemDo::getOrderId, orderId).orderByAsc(OrderItemDo::getId));
    }

    private List<OrderOperationLogDo> operations(Long orderId) {
        return logMapper.selectList(Wrappers.<OrderOperationLogDo>lambdaQuery()
                .eq(OrderOperationLogDo::getOrderId, orderId).orderByAsc(OrderOperationLogDo::getId));
    }

    /** JDK Mapper代理可能包装在多个Spring代理内，只定位Mockito观察对象，业务仍走原注入Bean。 */
    private static <T> T mapperObserver(T bean) {
        T observer = bean;
        while (!mockingDetails(observer).isMock()) {
            T target = AopTestUtils.getTargetObject(observer);
            assertNotSame(observer, target, "Mapper代理链中未找到Mockito包装");
            observer = target;
        }
        return observer;
    }
}
