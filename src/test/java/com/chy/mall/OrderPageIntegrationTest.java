package com.chy.mall;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.dto.*;
import com.chy.mall.entity.*;
import com.chy.mall.enums.*;
import com.chy.mall.mapper.*;
import com.chy.mall.security.AuthenticatedUser;
import com.chy.mall.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 订单分页与单笔详情的真实MySQL/MockMvc验收；关闭调度，只操作专用数据。 */
@SpringBootTest(properties = "mall.order.close-schedule.enabled=false")
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "mall.order.page.enabled", matches = "true")
class OrderPageIntegrationTest {
    @Autowired private OrderService service;
    @Autowired private OrderMapper orders;
    @Autowired private OrderItemMapper items;
    @Autowired private OrderOperationLogMapper operations;
    @Autowired private UserMapper users;
    @Autowired private ProductMapper products;
    @Autowired private ProductInventoryMapper inventories;
    @Autowired private PasswordEncoder encoder;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String secret = Base64.getEncoder().encodeToString(key);
        registry.add("jwt.secret", () -> secret);
    }

    @Test
    void pagesOnlyCurrentBuyerWithStableOrderDetailsAndValidatedHttpParameters() throws Exception {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
        }
        List<Long> userIds = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        ProductDo product = new ProductDo();
        boolean passed = false;
        try {
            String passwordHash = encoder.encode(UUID.randomUUID().toString());
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                for (int index = 0; index < 2; index++) {
                    UserDo user = new UserDo();
                    user.setUsername("page_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                    user.setPassword(passwordHash);
                    user.setRole(UserRole.USER);
                    user.setEnabled(true);
                    assertEquals(1, users.insert(user));
                    userIds.add(user.getId());
                }
                product.setSku("PAGE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 25));
                product.setName("订单分页专用商品");
                product.setPrice(new BigDecimal("9.90"));
                product.setStatus(ProductStatus.ON_SALE);
                assertEquals(1, products.insert(product));
                ProductInventoryDo inventory = new ProductInventoryDo();
                inventory.setProductId(product.getId());
                inventory.setStock(20);
                assertEquals(1, inventories.insert(inventory));
            });
            Long buyer = userIds.getFirst();
            for (int index = 0; index < 4; index++) {
                CreateOrderItemRequest item = new CreateOrderItemRequest();
                item.setProductId(product.getId());
                item.setQuantity(2);
                CreateOrderRequest request = new CreateOrderRequest();
                request.setRequestId(UUID.randomUUID().toString());
                request.setItems(List.of(item));
                orderIds.add(service.create(index < 3 ? buyer : userIds.get(1), request).getId());
            }
            // 相同创建时间检验ID倒序；仅调整本次数据，保持明细/CREATE与30分钟期限一致。
            LocalDateTime createdAt = LocalDateTime.now().withNano(0).minusMinutes(1);
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                assertEquals(4, orders.update(null, Wrappers.<OrderDo>lambdaUpdate().in(OrderDo::getId, orderIds)
                        .set(OrderDo::getCreatedAt, createdAt).set(OrderDo::getUpdatedAt, createdAt)
                        .set(OrderDo::getExpiresAt, createdAt.plusMinutes(30))));
                assertEquals(4, items.update(null, Wrappers.<OrderItemDo>lambdaUpdate().in(OrderItemDo::getOrderId, orderIds)
                        .set(OrderItemDo::getCreatedAt, createdAt).set(OrderItemDo::getUpdatedAt, createdAt)));
                assertEquals(4, operations.update(null, Wrappers.<OrderOperationLogDo>lambdaUpdate()
                        .in(OrderOperationLogDo::getOrderId, orderIds).eq(OrderOperationLogDo::getAction, OrderOperationAction.CREATE)
                        .set(OrderOperationLogDo::getCreatedAt, createdAt)));
            });
            service.cancel(buyer, orderIds.get(2));
            List<OrderDo> before = orders.selectList(Wrappers.<OrderDo>lambdaQuery()
                    .in(OrderDo::getId, orderIds).orderByAsc(OrderDo::getId));
            int stock = inventories.selectById(product.getId()).getStock();
            OrderPageQuery query = new OrderPageQuery();
            query.setSize(2);
            var first = service.page(buyer, query);
            assertEquals(3L, first.getTotal());
            assertEquals(List.of(orderIds.get(2), orderIds.get(1)), first.getRecords().stream().map(o -> o.getId()).toList());
            for (var order : first.getRecords()) {
                assertEquals(1, order.getItems().size());
                assertEquals(product.getId(), order.getItems().getFirst().getProductId());
                assertEquals(2, order.getItems().getFirst().getQuantity());
                assertEquals(new BigDecimal("19.80"), order.getTotalAmount());
            }
            query.setPage(2);
            assertEquals(List.of(orderIds.getFirst()), service.page(buyer, query).getRecords().stream().map(o -> o.getId()).toList());
            query.setPage(1);
            query.setStatus(OrderStatus.PENDING_PAYMENT);
            var pending = service.page(buyer, query);
            assertEquals(2L, pending.getTotal());
            assertEquals(List.of(orderIds.get(1), orderIds.getFirst()), pending.getRecords().stream().map(o -> o.getId()).toList());
            query.setStatus(OrderStatus.CANCELLED);
            assertEquals(orderIds.get(2), service.page(buyer, query).getRecords().getFirst().getId());
            query.setStatus(OrderStatus.PAID);
            assertEquals(0L, service.page(buyer, query).getTotal());
            assertTrue(service.page(buyer, query).getRecords().isEmpty());
            query.setStatus(null);
            query.setPage(100);
            assertEquals(3L, service.page(buyer, query).getTotal());
            assertTrue(service.page(buyer, query).getRecords().isEmpty());
            assertEquals(1L, service.page(userIds.get(1), new OrderPageQuery()).getTotal());

            var principal = UsernamePasswordAuthenticationToken.authenticated(
                    new AuthenticatedUser(buyer, "page-test", UserRole.USER), null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));
            mvc.perform(get("/orders").with(authentication(principal)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.size").value(10))
                    .andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.records.length()").value(3))
                    .andExpect(jsonPath("$.data.records[0].id").value(orderIds.get(2)))
                    .andExpect(jsonPath("$.data.records[0].items[0].quantity").value(2));
            mvc.perform(get("/orders").param("status", "CANCELLED").param("buyerId", userIds.get(1).toString())
                            .with(authentication(principal)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.records[0].id").value(orderIds.get(2)));
            for (String[] invalid : List.of(new String[]{"page", "0"}, new String[]{"size", "101"}, new String[]{"status", "UNKNOWN"})) {
                mvc.perform(get("/orders").param(invalid[0], invalid[1]).with(authentication(principal)))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
            }
            // 单笔详情复用本次独立数据，真实验证归属过滤、错误响应和成交快照。
            Long detailId = orderIds.getFirst();
            assertEquals(detailId, service.findById(buyer, detailId).getId());
            assertNull(orders.selectById(Long.MAX_VALUE), "不存在订单的验收ID必须确实不存在");
            // 商品后续改名/改价，订单详情仍应展示成交时的信息。
            new TransactionTemplate(transactions).executeWithoutResult(tx ->
                    assertEquals(1, products.update(null, Wrappers.<ProductDo>lambdaUpdate()
                            .eq(ProductDo::getId, product.getId())
                            .set(ProductDo::getName, "修改后的专用商品")
                            .set(ProductDo::getPrice, new BigDecimal("15.00")))));
            mvc.perform(get("/orders/{orderId}", detailId).with(authentication(principal)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.id").value(detailId))
                    .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                    .andExpect(jsonPath("$.data.totalAmount").value(19.80))
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].productId").value(product.getId()))
                    .andExpect(jsonPath("$.data.items[0].name").value(product.getName()))
                    .andExpect(jsonPath("$.data.items[0].sku").value(product.getSku()))
                    .andExpect(jsonPath("$.data.items[0].unitPrice").value(9.90))
                    .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                    .andExpect(jsonPath("$.data.items[0].lineAmount").value(19.80));
            for (Long hiddenId : List.of(orderIds.get(3), Long.MAX_VALUE)) {
                mvc.perform(get("/orders/{orderId}", hiddenId).with(authentication(principal))
                                .param("buyerId", userIds.get(1).toString()))
                        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40402))
                        .andExpect(jsonPath("$.data").doesNotExist());
            }
            for (Long invalidId : List.of(0L, -1L)) {
                mvc.perform(get("/orders/{orderId}", invalidId).with(authentication(principal)))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
            }
            // 模拟认证信息中的买家ID异常，确认错误由真实Service统一返回40301。
            var invalidBuyerPrincipal = UsernamePasswordAuthenticationToken.authenticated(
                    new AuthenticatedUser(0L, "invalid-buyer-test", UserRole.USER), null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER")));
            for (String path : List.of("/orders", "/orders/" + detailId)) {
                mvc.perform(get(path).with(authentication(invalidBuyerPrincipal)))
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(40301));
            }
            assertEquals(before, orders.selectList(Wrappers.<OrderDo>lambdaQuery()
                    .in(OrderDo::getId, orderIds).orderByAsc(OrderDo::getId)));
            assertEquals(stock, inventories.selectById(product.getId()).getStock());
            passed = true;
        } finally {
            if (passed) {
                new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    operations.delete(Wrappers.<OrderOperationLogDo>lambdaQuery().in(OrderOperationLogDo::getOrderId, orderIds));
                    items.delete(Wrappers.<OrderItemDo>lambdaQuery().in(OrderItemDo::getOrderId, orderIds));
                    assertEquals(4, orders.delete(Wrappers.<OrderDo>lambdaQuery().in(OrderDo::getId, orderIds)));
                    assertEquals(1, inventories.deleteById(product.getId()));
                    assertEquals(1, products.deleteById(product.getId()));
                    assertEquals(2, users.delete(Wrappers.<UserDo>lambdaQuery().in(UserDo::getId, userIds)));
                });
                assertEquals(0L, orders.selectCount(Wrappers.<OrderDo>lambdaQuery().in(OrderDo::getId, orderIds)));
                assertEquals(0L, items.selectCount(Wrappers.<OrderItemDo>lambdaQuery().in(OrderItemDo::getOrderId, orderIds)));
                assertEquals(0L, operations.selectCount(Wrappers.<OrderOperationLogDo>lambdaQuery().in(OrderOperationLogDo::getOrderId, orderIds)));
                assertNull(products.selectById(product.getId()));
                assertNull(inventories.selectById(product.getId()));
                assertEquals(0L, users.selectCount(Wrappers.<UserDo>lambdaQuery().in(UserDo::getId, userIds)));
            } else {
                System.err.printf("分页测试失败，保留专用现场：userIds=%s, orderIds=%s, productId=%s%n", userIds, orderIds, product.getId());
            }
        }
    }
}
