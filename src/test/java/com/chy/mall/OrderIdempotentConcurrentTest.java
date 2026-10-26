package com.chy.mall;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.dto.CreateOrderItemRequest;
import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.OrderStatus;
import com.chy.mall.enums.OrderOperationAction;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.mapper.OrderItemMapper;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.mapper.OrderOperationLogMapper;
import com.chy.mall.entity.OrderOperationLogDo;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.service.OrderService;
import com.chy.mall.service.impl.OrderCreateTransactionService;
import com.chy.mall.vo.OrderVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 并发幂等下单真实MySQL测试：验证同一requestId并发提交只生成一笔订单、
 * 库存只扣一次，且所有线程都能拿到相同的订单结果。
 *
 * 运行：./mvnw -Dtest=OrderIdempotentConcurrentTest -Dmall.order.concurrent.enabled=true test
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "mall.order.concurrent.enabled", matches = "true")
public class OrderIdempotentConcurrentTest {

    @Autowired
    private OrderService orderService;
    @MockitoSpyBean
    private OrderCreateTransactionService orderCreateTransactionService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ProductInventoryMapper inventoryMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private OrderOperationLogMapper operationLogMapper;

    /**
     * 场景一：两个请求均通过入口未命中后，竞争同一个requestId和最后一件库存。
     * 预期：一个真实重复键异常被外层恢复，两个请求均返回同一笔订单。
     */
    @Test
    void concurrentSameRequestIdProducesExactlyOneOrder() throws Exception {
        ProductDo fixture = createFixture("CONC-SAME-", "并发幂等测试商品", "25.00", 1);
        Long productId = fixture.getId();
        String requestId = UUID.randomUUID().toString();
        int threadCount = 2;
        CyclicBarrier createBarrier = new CyclicBarrier(threadCount);
        AtomicInteger duplicateKeyFailures = new AtomicInteger();
        List<Future<OrderVo>> futures = new ArrayList<>(threadCount);
        ExecutorService executor = null;

        try {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "测试自身不能开启事务");
            assertTrue(AopUtils.isAopProxy(orderCreateTransactionService), "必须通过事务代理调用创建方法");
            // 只解包用于设置Spy；业务调用仍由orderService经过Spring事务代理。
            OrderCreateTransactionService transactionSpy = AopTestUtils.getUltimateTargetObject(orderCreateTransactionService);
            doAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "真实创建必须位于业务事务中");
                // 到达这里说明入口查询已未命中；两方都到齐后才执行任何真实创建逻辑。
                createBarrier.await(10, TimeUnit.SECONDS);
                try {
                    return invocation.callRealMethod();
                } catch (DuplicateKeyException exception) {
                    duplicateKeyFailures.incrementAndGet();
                    throw exception;
                }
            }).when(transactionSpy).createNewOrder(eq(1L), eq(requestId), anyString(), any());

            executor = Executors.newFixedThreadPool(threadCount);
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> orderService.create(1L, buildRequest(requestId, productId, 1))));
            }
            List<OrderVo> results = new ArrayList<>(threadCount);
            for (Future<OrderVo> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }

            assertEquals(threadCount, results.size(), "两个请求都应成功返回");
            verify(transactionSpy, times(threadCount)).createNewOrder(eq(1L), eq(requestId), anyString(), any());
            assertEquals(1, duplicateKeyFailures.get(), "必须真实触发一次重复键异常并由外层恢复");

            Long expectedOrderId = results.getFirst().getId();
            String expectedOrderNo = results.getFirst().getOrderNo();
            assertNotNull(expectedOrderId);
            assertNotNull(expectedOrderNo);
            assertFalse(expectedOrderNo.isBlank());
            for (OrderVo vo : results) {
                assertEquals(expectedOrderId, vo.getId(), "两个请求应返回同一笔订单");
                assertEquals(expectedOrderNo, vo.getOrderNo(), "两个请求应返回同一订单号");
                assertEquals(OrderStatus.PENDING_PAYMENT, vo.getStatus());
                assertEquals(0, new BigDecimal("25.00").compareTo(vo.getTotalAmount()));
                assertEquals(1, vo.getItems().size());
                assertEquals(productId, vo.getItems().getFirst().getProductId());
                assertEquals(1, vo.getItems().getFirst().getQuantity());
            }

            Long orderCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE buyer_id = 1 AND request_id = ?",
                    Long.class, requestId);
            assertEquals(1L, orderCount, "数据库中应恰好一笔订单");
            Long persistedOrderId = jdbcTemplate.queryForObject(
                    "SELECT id FROM orders WHERE buyer_id = 1 AND request_id = ?",
                    Long.class, requestId);
            assertEquals(expectedOrderId, persistedOrderId, "响应应对应真实保存的订单");
            Long itemCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM order_items WHERE order_id = ?",
                    Long.class, expectedOrderId);
            assertEquals(1L, itemCount, "数据库中应恰好一条订单明细");
            assertEquals(1L, operationLogMapper.selectCount(Wrappers.<OrderOperationLogDo>lambdaQuery()
                    .eq(OrderOperationLogDo::getOrderId, expectedOrderId)
                    .eq(OrderOperationLogDo::getAction, OrderOperationAction.CREATE)),
                    "并发幂等下单只能生成一条CREATE流水");
            Integer savedQuantity = jdbcTemplate.queryForObject(
                    "SELECT quantity FROM order_items WHERE order_id = ? AND product_id = ?",
                    Integer.class, expectedOrderId, productId);
            assertEquals(1, savedQuantity, "真实明细购买数量应为1");

            ProductInventoryDo inventory = inventoryMapper.selectById(productId);
            assertNotNull(inventory);
            assertEquals(0, inventory.getStock(), "最后一件库存应只被扣减一次");
        } finally {
            if (executor != null) {
                for (Future<OrderVo> future : futures) {
                    if (!future.isDone()) {
                        future.cancel(true);
                    }
                }
                executor.shutdownNow();
                try {
                    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                            "并发任务未及时结束，保留本次专用数据，避免清理与下单竞争");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
            // 确认任务已结束后才清理；等待超时或被中断时保留数据。
            cleanupByRequestId(requestId, productId);
        }
    }

    /**
     * 场景二：2个线程用同一个requestId，但商品数量不同。
     * 预期：一个成功创建订单，另一个收到409 ORDER_CONFLICT。
     */
    @Test
    void concurrentConflictingFingerprintReturnsConflict() throws Exception {
        ProductDo fixture = createFixture("CONC-CONF-", "并发指纹冲突测试商品", "10.00", 100);
        Long productId = fixture.getId();
        String requestId = UUID.randomUUID().toString();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CyclicBarrier barrier = new CyclicBarrier(2);

            // 两个线程对称处理：谁抢到唯一键不确定，两方都必须捕获BusinessException。
            Future<Object> futureA = executor.submit(() -> {
                barrier.await();
                try {
                    return orderService.create(1L, buildRequest(requestId, productId, 2));
                } catch (BusinessException e) {
                    return e;
                }
            });
            Future<Object> futureB = executor.submit(() -> {
                barrier.await();
                try {
                    return orderService.create(1L, buildRequest(requestId, productId, 3));
                } catch (BusinessException e) {
                    return e;
                }
            });

            Object resultA = futureA.get();
            Object resultB = futureB.get();

            // 两个结果中，恰好一个是OrderVo，一个是BusinessException
            OrderVo successVo;
            BusinessException conflictException;
            if (resultA instanceof OrderVo) {
                successVo = (OrderVo) resultA;
                assertInstanceOf(BusinessException.class, resultB, "另一个线程应抛出BusinessException");
                conflictException = (BusinessException) resultB;
            } else {
                assertInstanceOf(BusinessException.class, resultA);
                conflictException = (BusinessException) resultA;
                assertInstanceOf(OrderVo.class, resultB, "必须有一个线程成功创建订单");
                successVo = (OrderVo) resultB;
            }

            assertEquals(OrderStatus.PENDING_PAYMENT, successVo.getStatus());
            assertEquals(40901, conflictException.getErrorCode().getCode(), "冲突应返回ORDER_CONFLICT错误码");

            // 数据库中只有一笔订单
            Long orderCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE buyer_id = 1 AND request_id = ?",
                    Long.class, requestId);
            assertEquals(1L, orderCount);

        } finally {
            cleanupByRequestId(requestId, productId);
        }
    }

    /**
     * 场景三：下单因库存不足失败后，补充库存后用同一requestId重试应成功。
     */
    @Test
    void retryAfterStockFailureWithSameRequestIdSucceeds() {
        ProductDo fixture = createFixture("CONC-RETRY-", "库存不足重试测试商品", "15.00", 1);
        Long productId = fixture.getId();
        String requestId = UUID.randomUUID().toString();

        try {
            // 第一次：库存只有1，买5件，应失败
            CreateOrderRequest request = buildRequest(requestId, productId, 5);
            assertThrows(BusinessException.class, () -> orderService.create(1L, request),
                    "库存不足应抛出异常");

            // 确认没有残留订单
            Long orderCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE buyer_id = 1 AND request_id = ?",
                    Long.class, requestId);
            assertEquals(0L, orderCount, "失败后不应残留订单记录");

            // 确认库存未被扣减
            ProductInventoryDo inventory = inventoryMapper.selectById(productId);
            assertEquals(1, inventory.getStock(), "失败后库存应保持不变");

            // 补充库存到10
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.update("UPDATE product_inventory SET stock = 10 WHERE product_id = ?", productId);
            });

            // 第二次：同一requestId重试，这次应成功
            OrderVo result = orderService.create(1L, request);
            assertNotNull(result.getId());
            assertEquals(OrderStatus.PENDING_PAYMENT, result.getStatus());
            assertEquals(0, new BigDecimal("75.00").compareTo(result.getTotalAmount()));

            // 库存从10扣到5
            inventory = inventoryMapper.selectById(productId);
            assertEquals(5, inventory.getStock());

        } finally {
            cleanupByRequestId(requestId, productId);
        }
    }

    // ==================== 请求构建 ====================

    private static CreateOrderRequest buildRequest(String requestId, Long productId, int quantity) {
        CreateOrderItemRequest item = new CreateOrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestId(requestId);
        request.setItems(List.of(item));
        return request;
    }

    // ==================== 测试数据管理 ====================

    private ProductDo createFixture(String skuPrefix, String name, String price, int stock) {
        String sku = skuPrefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        ProductDo fixture = new ProductDo();
        fixture.setSku(sku);
        fixture.setName(name);
        fixture.setPrice(new BigDecimal(price));
        fixture.setStatus(ProductStatus.ON_SALE);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertEquals(1, productMapper.insert(fixture));
            assertNotNull(fixture.getId());
            ProductInventoryDo inventory = new ProductInventoryDo();
            inventory.setProductId(fixture.getId());
            inventory.setStock(stock);
            assertEquals(1, inventoryMapper.insert(inventory));
        });
        return fixture;
    }

    private void cleanupByRequestId(String requestId, Long productId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // 先查出订单ID用于删明细
            List<Long> orderIds = jdbcTemplate.queryForList(
                    "SELECT id FROM orders WHERE request_id = ?", Long.class, requestId);
            for (Long orderId : orderIds) {
                operationLogMapper.delete(Wrappers.<OrderOperationLogDo>lambdaQuery().eq(OrderOperationLogDo::getOrderId, orderId));
                jdbcTemplate.update("DELETE FROM order_items WHERE order_id = ?", orderId);
            }
            jdbcTemplate.update("DELETE FROM orders WHERE request_id = ?", requestId);
            inventoryMapper.deleteById(productId);
            productMapper.deleteById(productId);
        });
    }
}
