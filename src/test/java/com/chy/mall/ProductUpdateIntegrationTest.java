package com.chy.mall;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.dto.*;
import com.chy.mall.entity.*;
import com.chy.mall.enums.*;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.*;
import com.chy.mall.security.AuthenticatedUser;
import com.chy.mall.service.OrderService;
import com.chy.mall.service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 商品资料编辑及上下架的真实MySQL/HTTP层验收，只用专用数据，成功清理、失败留证。 */
@SpringBootTest(properties = "mall.order.close-schedule.enabled=false")
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "mall.product.update.enabled", matches = "true")
class ProductUpdateIntegrationTest {
    @Autowired private ProductService service;
    @Autowired private OrderService orderService;
    @Autowired private ProductMapper products;
    @Autowired private ProductInventoryMapper inventories;
    @Autowired private UserMapper users;
    @Autowired private OrderMapper orders;
    @Autowired private OrderItemMapper items;
    @Autowired private OrderOperationLogMapper operations;
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
    void updatesOnlyEditableFieldsPreservesOrderSnapshotsAndRollsBackOnAssemblyFailure() throws Exception {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
        }
        UserDo user = new UserDo();
        Long productId = null;
        List<Long> orderIds = new ArrayList<>();
        boolean passed = false;
        try {
            user.setUsername("edit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            user.setPassword(encoder.encode(UUID.randomUUID().toString()));
            user.setRole(UserRole.ADMIN);
            user.setEnabled(true);
            new TransactionTemplate(transactions).executeWithoutResult(tx -> assertEquals(1, users.insert(user)));
            CreateProductRequest create = new CreateProductRequest();
            create.setSku("EDIT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            create.setName("原商品名称");
            create.setPrice(new BigDecimal("9.90"));
            create.setInitialStock(10);
            productId = service.create(create).getId();
            Long fixtureProductId = productId;
            Long oldOrderId = createOrder(user.getId(), productId);
            orderIds.add(oldOrderId);
            ProductDo before = products.selectById(productId);
            ProductInventoryDo stockBefore = inventories.selectById(productId);

            var principal = UsernamePasswordAuthenticationToken.authenticated(
                    new AuthenticatedUser(user.getId(), user.getUsername(), UserRole.ADMIN), null,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            String body = "{\"name\":\"编辑后的商品\",\"price\":129.90}";
            mvc.perform(put("/products/{id}", productId).with(authentication(principal))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.id").value(productId))
                    .andExpect(jsonPath("$.data.name").value("编辑后的商品"))
                    .andExpect(jsonPath("$.data.price").value(129.90))
                    .andExpect(jsonPath("$.data.stock").value(stockBefore.getStock()));

            ProductDo changed = products.selectById(productId);
            assertEquals("编辑后的商品", changed.getName());
            assertEquals(new BigDecimal("129.90"), changed.getPrice());
            assertEquals(before.getSku(), changed.getSku());
            assertEquals(before.getStatus(), changed.getStatus());
            assertEquals(before.getCreatedAt(), changed.getCreatedAt());
            assertEquals(stockBefore, inventories.selectById(productId));

            // 同值再次提交仍成功，不因驱动“匹配行/变化行”统计差异误报404。
            mvc.perform(put("/products/{id}", productId).with(authentication(principal))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
            assertEquals(stockBefore, inventories.selectById(productId));

            var oldOrder = orderService.findById(user.getId(), oldOrderId);
            assertEquals("原商品名称", oldOrder.getItems().getFirst().getName());
            assertEquals(new BigDecimal("9.90"), oldOrder.getItems().getFirst().getUnitPrice());
            assertEquals(new BigDecimal("9.90"), oldOrder.getTotalAmount());
            Long newOrderId = createOrder(user.getId(), productId);
            orderIds.add(newOrderId);
            var newOrder = orderService.findById(user.getId(), newOrderId);
            assertEquals("编辑后的商品", newOrder.getItems().getFirst().getName());
            assertEquals(new BigDecimal("129.90"), newOrder.getItems().getFirst().getUnitPrice());
            assertEquals(new BigDecimal("129.90"), newOrder.getTotalAmount());

            // 复用本轮商品和订单验证上下架，避免重复搭建一套真实库fixture。
            ProductDo beforeStatus = products.selectById(productId);
            ProductInventoryDo stockBeforeStatus = inventories.selectById(productId);
            OrderDo oldOrderBeforeStatus = orders.selectById(oldOrderId);
            for (int attempt = 0; attempt < 2; attempt++) {
                mvc.perform(put("/products/{id}/status", productId).with(authentication(principal))
                                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OFF_SALE\"}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                        .andExpect(jsonPath("$.data.status").value("OFF_SALE"))
                        .andExpect(jsonPath("$.data.stock").value(stockBeforeStatus.getStock()));
            }
            ProductDo offSale = products.selectById(productId);
            assertEquals(ProductStatus.OFF_SALE, offSale.getStatus());
            assertEquals(beforeStatus.getName(), offSale.getName());
            assertEquals(beforeStatus.getPrice(), offSale.getPrice());
            assertEquals(beforeStatus.getSku(), offSale.getSku());
            assertEquals(beforeStatus.getCreatedAt(), offSale.getCreatedAt());
            assertEquals(stockBeforeStatus, inventories.selectById(productId));
            assertEquals(oldOrderBeforeStatus, orders.selectById(oldOrderId));

            CreateOrderRequest blockedRequest = orderRequest(productId);
            assertEquals(ErrorCode.ORDER_CONFLICT,
                    assertThrows(BusinessException.class,
                            () -> orderService.create(user.getId(), blockedRequest)).getErrorCode());
            assertEquals((long) orderIds.size(),
                    orders.selectCount(Wrappers.<OrderDo>lambdaQuery().eq(OrderDo::getBuyerId, user.getId())));
            assertEquals(stockBeforeStatus, inventories.selectById(productId));
            // 同一请求号重试已有订单仍按幂等契约返回，不是下架后再创建一笔。
            CreateOrderRequest replay = orderRequest(productId);
            replay.setRequestId(oldOrderBeforeStatus.getRequestId());
            assertEquals(oldOrderId, orderService.create(user.getId(), replay).getId());
            assertEquals(stockBeforeStatus, inventories.selectById(productId));
            assertEquals(OrderStatus.PAID, orderService.pay(user.getId(), oldOrderId).getStatus());
            assertEquals(stockBeforeStatus, inventories.selectById(productId), "下架不影响旧订单支付，也不重复扣库存");

            mvc.perform(put("/products/{id}/status", productId).with(authentication(principal))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ON_SALE\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ON_SALE"));
            assertEquals(stockBeforeStatus, inventories.selectById(productId));
            orderIds.add(orderService.create(user.getId(), blockedRequest).getId());
            assertEquals(stockBeforeStatus.getStock() - 1, inventories.selectById(productId).getStock());
            ProductDo statusBaseline = products.selectById(productId);
            for (String invalidStatus : List.of("{}", "{\"status\":null}", "{\"status\":\"UNKNOWN\"}")) {
                mvc.perform(put("/products/{id}/status", productId).with(authentication(principal))
                                .contentType(MediaType.APPLICATION_JSON).content(invalidStatus))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
            }
            mvc.perform(put("/products/{id}/status", 0).with(authentication(principal))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OFF_SALE\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
            assertNull(products.selectById(Long.MAX_VALUE));
            mvc.perform(put("/products/{id}/status", Long.MAX_VALUE).with(authentication(principal))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OFF_SALE\"}"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40401));
            assertEquals(statusBaseline, products.selectById(productId));

            ProductDo validBaseline = products.selectById(productId);
            ProductInventoryDo validStock = inventories.selectById(productId);
            for (String invalidBody : List.of(
                    "{\"name\":\" \",\"price\":1}",
                    "{\"name\":\"" + "x".repeat(101) + "\",\"price\":1}",
                    "{\"name\":\"编辑商品\",\"price\":0}",
                    "{\"name\":\"编辑商品\",\"price\":0.001}",
                    "{\"name\":\"编辑商品\",\"price\":10000000000}",
                    "{\"name\":\"编辑商品\"}",
                    "{\"price\":1}")) {
                mvc.perform(put("/products/{id}", productId).with(authentication(principal))
                                .contentType(MediaType.APPLICATION_JSON).content(invalidBody))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
            }
            mvc.perform(put("/products/{id}", 0).with(authentication(principal))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(40001));
            assertNull(products.selectById(Long.MAX_VALUE));
            mvc.perform(put("/products/{id}", Long.MAX_VALUE).with(authentication(principal))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(40401));
            assertEquals(validBaseline, products.selectById(productId));
            assertEquals(validStock, inventories.selectById(productId));

            // 独立提交删除专用库存，制造更新后的返回组装失败，验证名称/价格也会回滚。
            new TransactionTemplate(transactions).executeWithoutResult(tx ->
                    assertEquals(1, inventories.deleteById(fixtureProductId)));
            UpdateProductRequest rollbackRequest = new UpdateProductRequest();
            rollbackRequest.setName("不应提交的名称");
            rollbackRequest.setPrice(new BigDecimal("149.90"));
            assertEquals(ErrorCode.PRODUCT_INVENTORY_MISSING,
                    assertThrows(BusinessException.class,
                            () -> service.update(fixtureProductId, rollbackRequest)).getErrorCode());
            assertEquals(validBaseline, products.selectById(productId));
            assertNull(inventories.selectById(productId), "准备阶段删除的库存不应被业务回滚恢复");
            UpdateProductStatusRequest rollbackStatus = new UpdateProductStatusRequest();
            rollbackStatus.setStatus(ProductStatus.OFF_SALE);
            assertEquals(ErrorCode.PRODUCT_INVENTORY_MISSING,
                    assertThrows(BusinessException.class,
                            () -> service.updateStatus(fixtureProductId, rollbackStatus)).getErrorCode());
            assertEquals(validBaseline, products.selectById(productId), "返回组装失败时，上下架状态也须回滚");
            passed = true;
        } finally {
            if (passed) {
                Long fixtureProductId = productId;
                new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    operations.delete(Wrappers.<OrderOperationLogDo>lambdaQuery().in(OrderOperationLogDo::getOrderId, orderIds));
                    items.delete(Wrappers.<OrderItemDo>lambdaQuery().in(OrderItemDo::getOrderId, orderIds));
                    assertEquals(orderIds.size(), orders.delete(Wrappers.<OrderDo>lambdaQuery().in(OrderDo::getId, orderIds)));
                    inventories.deleteById(fixtureProductId);
                    assertEquals(1, products.deleteById(fixtureProductId));
                    assertEquals(1, users.deleteById(user.getId()));
                });
                assertEquals(0L, orders.selectCount(Wrappers.<OrderDo>lambdaQuery().in(OrderDo::getId, orderIds)));
                assertEquals(0L, items.selectCount(Wrappers.<OrderItemDo>lambdaQuery().in(OrderItemDo::getOrderId, orderIds)));
                assertEquals(0L, operations.selectCount(Wrappers.<OrderOperationLogDo>lambdaQuery().in(OrderOperationLogDo::getOrderId, orderIds)));
                assertNull(products.selectById(productId));
                assertNull(inventories.selectById(productId));
                assertNull(users.selectById(user.getId()));
            } else {
                System.err.printf("商品编辑验收失败，保留专用现场：userId=%s, productId=%s, orderIds=%s%n",
                        user.getId(), productId, orderIds);
            }
        }
    }

    private Long createOrder(Long buyerId, Long productId) {
        return orderService.create(buyerId, orderRequest(productId)).getId();
    }

    private CreateOrderRequest orderRequest(Long productId) {
        CreateOrderItemRequest item = new CreateOrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(1);
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setItems(List.of(item));
        return request;
    }
}
