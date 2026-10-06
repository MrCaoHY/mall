package com.chy.mall;

import com.chy.mall.dto.CreateOrderItemRequest;
import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.entity.OrderItemDo;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.OrderStatus;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.mapper.OrderItemMapper;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.service.impl.OrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 真实MySQL事务测试：仅在明细保存处注入异常，其余写入由真实Mapper执行。
 * 测试本身没有@Transactional，避免测试自动回滚掩盖Service事务的问题。
 * 运行：./mvnw -Dtest=OrderCreateRollbackTest -Dmall.order.rollback.enabled=true test
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "mall.order.rollback.enabled", matches = "true")
public class OrderCreateRollbackTest {
    @Autowired
    private OrderServiceImpl orderService;
    @MockitoBean(enforceOverride = true)
    private OrderItemMapper orderItemMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ProductInventoryMapper inventoryMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void detailSaveFailureRollsBackRealStockAndOrder() {
        assertTrue(AopUtils.isAopProxy(orderService), "必须通过Spring事务代理调用Service");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "测试自身不能开启事务");

        // 专用随机商品独立提交，不使用或修改已有商品20/60等业务数据。
        ProductDo fixture = createFixture();
        Long productId = fixture.getId();
        AtomicReference<Long> insertedOrderId = new AtomicReference<>();
        IllegalStateException injectedFailure =
                new IllegalStateException("测试注入：订单明细保存失败");

        try {
            CreateOrderItemRequest item = new CreateOrderItemRequest();
            item.setProductId(productId);
            item.setQuantity(2);
            CreateOrderRequest request = new CreateOrderRequest();
            request.setItems(List.of(item));

            doAnswer(invocation -> {
                List<OrderItemDo> items = invocation.getArgument(0);
                assertEquals(1, items.size());
                Long orderId = items.getFirst().getOrderId();
                insertedOrderId.set(orderId);
                assertNotNull(orderId, "主表插入后应回填订单ID");
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                        "明细保存阶段必须位于create的业务事务中");

                // 在异常前查真实数据库：证明库存和主表已经写入当前事务。
                ProductInventoryDo inventory = inventoryMapper.selectById(productId);
                assertNotNull(inventory);
                assertEquals(3, inventory.getStock().intValue());
                OrderDo order = orderMapper.selectById(orderId);
                assertNotNull(order, "注入异常前主表必须真实存在");
                assertEquals(1L, order.getBuyerId().longValue());
                assertEquals(OrderStatus.PENDING_PAYMENT, order.getStatus());
                assertEquals(new BigDecimal("25.00"), order.getTotalAmount());
                assertEquals(productId, items.getFirst().getProductId());
                assertEquals(2, items.getFirst().getQuantity().intValue());
                throw injectedFailure;
            }).when(orderItemMapper).batchInsert(anyList());

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> orderService.create(1L, request));
            assertSame(injectedFailure, thrown, "必须失败在指定明细步骤，而不是其他前置检查");
            verify(orderItemMapper, times(1)).batchInsert(anyList());
            assertNotNull(insertedOrderId.get());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Service抛异常返回后，业务事务应已结束");

            // 在事务外回查已提交数据，而不是依靠测试结束时自动回滚。
            ProductInventoryDo restored = inventoryMapper.selectById(productId);
            assertNotNull(restored);
            assertEquals(5, restored.getStock().intValue(), "库存应从3恢复到初值5");
            assertNull(orderMapper.selectById(insertedOrderId.get()), "订单主表应被回滚");
            // 明细Mapper已经被mock，使用JdbcTemplate读取真实表，避免mock的假0。
            assertEquals(0L, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM order_items WHERE order_id = ?",
                    Long.class, insertedOrderId.get()).longValue());
        } finally {
            cleanupFixture(productId, insertedOrderId.get());
        }
    }

    private ProductDo createFixture() {
        String sku = "ORDER-RB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        ProductDo fixture = product(null, sku, "订单明细失败回滚测试商品", "12.50");
        // 仅用于原子准备并提交fixture，不包住orderService.create调用。
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertEquals(1, productMapper.insert(fixture));
            assertNotNull(fixture.getId());
            ProductInventoryDo inventory = new ProductInventoryDo();
            inventory.setProductId(fixture.getId());
            inventory.setStock(5);
            assertEquals(1, inventoryMapper.insert(inventory));
        });
        return fixture;
    }

    private void cleanupFixture(Long productId, Long orderId) {
        long remainingItems = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_items WHERE product_id = ?", Long.class, productId);
        boolean remainingOrder = orderId != null && orderMapper.selectById(orderId) != null;
        if (remainingItems != 0 || remainingOrder) {
            // 回滚失败时保留本次证据，避免清理动作掩盖问题或碰到关联数据。
            System.err.printf("回滚测试有残留，保留专用商品：productId=%s, orderId=%s%n", productId, orderId);
            return;
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertEquals(1, inventoryMapper.deleteById(productId));
            assertEquals(1, productMapper.deleteById(productId));
        });
    }

    private static ProductDo product(Long id, String sku, String name, String price) {
        ProductDo product = new ProductDo();
        product.setId(id);
        product.setSku(sku);
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        product.setStatus(ProductStatus.ON_SALE);
        return product;
    }
}