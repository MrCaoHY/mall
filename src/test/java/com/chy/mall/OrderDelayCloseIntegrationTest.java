package com.chy.mall;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.entity.OrderItemDo;
import com.chy.mall.entity.OrderOperationLogDo;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.entity.UserDo;
import com.chy.mall.enums.OrderOperationAction;
import com.chy.mall.enums.OrderStatus;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.enums.UserRole;
import com.chy.mall.mapper.OrderItemMapper;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.mapper.OrderOperationLogMapper;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.mapper.UserMapper;
import com.chy.mall.security.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RabbitMQ TTL+DLX 延迟关单【真实HTTP下单全链路】端到端集成测试：
 * 测试不再手动插入假订单或手动调用RabbitTemplate，而是完整走：
 * POST /orders/create -> OrderService -> OrderCreateTransactionService (MySQL事务提交)
 * -> afterCommit自动投递RabbitMQ延迟队列 (2000ms) -> 到期转入死信队列
 * -> OrderCloseConsumer自动消费 -> OrderCloseTransactionService自动关单、回补库存并记CLOSE流水。
 */
@SpringBootTest(properties = {
        "mall.order.close-schedule.enabled=false",
        "mall.order.close-delay-ms=2000"
})
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "mall.order.delay.enabled", matches = "true")
class OrderDelayCloseIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductInventoryMapper inventoryMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private OrderOperationLogMapper operationLogMapper;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String secret = Base64.getEncoder().encodeToString(key);
        registry.add("jwt.secret", () -> secret);
    }

    @Test
    @DisplayName("真实全链路：POST /orders/create下单 -> afterCommit自动发MQ -> 2秒到期自動关单回补库存并记CLOSE流水；已支付订单到期幂等跳过")
    void realHttpOrderCreateAndPayFullChainWithRabbitMqDelayClose() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long buyerId = null;
        Long productId = null;
        List<Long> createdOrderIds = new ArrayList<>();

        try {
            // 1. 准备真实测试买家与商品（初始库存 10，单价 88.00）
            UserDo buyer = new UserDo();
            buyer.setUsername("mq_buyer_" + suffix);
            buyer.setPassword(passwordEncoder.encode("TestPass123!"));
            buyer.setRole(UserRole.USER);
            buyer.setEnabled(true);
            userMapper.insert(buyer);
            buyerId = buyer.getId();

            ProductDo product = new ProductDo();
            product.setSku("MQ-CHAIN-" + suffix);
            product.setName("全链路延迟关单商品-" + suffix);
            product.setPrice(new BigDecimal("88.00"));
            product.setStatus(ProductStatus.ON_SALE);
            product.setCreatedAt(LocalDateTime.now());
            product.setUpdatedAt(LocalDateTime.now());
            productMapper.insert(product);
            productId = product.getId();

            ProductInventoryDo inventory = new ProductInventoryDo();
            inventory.setProductId(productId);
            inventory.setStock(10);
            inventoryMapper.insert(inventory);

            var buyerPrincipal = UsernamePasswordAuthenticationToken.authenticated(
                    new AuthenticatedUser(buyerId, buyer.getUsername(), UserRole.USER),
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );

            // =========================================================================
            // 场景 A：真实 HTTP 下单（购买 2 件）-> 不支付 -> 等待 2 秒自动延迟关单与库存回补
            // =========================================================================
            String createJson1 = """
                    {
                      "requestId": "%s",
                      "items": [
                        {"productId": %d, "quantity": 2}
                      ]
                    }
                    """.formatted(UUID.randomUUID().toString(), productId);

            MvcResult createResult1 = mvc.perform(post("/orders/create")
                            .with(authentication(buyerPrincipal))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createJson1))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                    .andReturn();

            JsonNode root1 = objectMapper.readTree(createResult1.getResponse().getContentAsString());
            Long expireOrderId = root1.path("data").path("id").asLong();
            createdOrderIds.add(expireOrderId);

            // 验证下单刚完成瞬间（t = 0s）：状态为 PENDING_PAYMENT，expiresAt = createdAt + 2秒，库存扣减为 8
            OrderDo rightAfterCreate = orderMapper.selectById(expireOrderId);
            assertNotNull(rightAfterCreate);
            assertEquals(OrderStatus.PENDING_PAYMENT, rightAfterCreate.getStatus());
            assertEquals(rightAfterCreate.getCreatedAt().plusSeconds(2), rightAfterCreate.getExpiresAt(),
                    "订单到期时间应与配置的2000ms延迟严格对齐");
            assertEquals(8, currentStock(productId), "下单后库存应立即从10扣减为8");

            // 等待 3.5 秒（让 2000ms TTL 到期 -> 死信路由 -> OrderCloseConsumer 消费关单）
            TimeUnit.MILLISECONDS.sleep(3500);

            // 验证自动关单结果（t = 3.5s）：状态变为 CLOSED，库存从 8 自动恢复为 10，生成 CREATE + CLOSE 两条流水
            OrderDo afterDelayClose = orderMapper.selectById(expireOrderId);
            assertNotNull(afterDelayClose);
            assertEquals(OrderStatus.CLOSED, afterDelayClose.getStatus(),
                    "真实下单触发afterCommit发出的MQ消息应在2秒到期后自动将订单关闭");
            assertEquals(10, currentStock(productId),
                    "自动关单后库存应从8恢复为10");

            List<OrderOperationLogDo> expireOrderLogs = operationLogMapper.selectList(
                    Wrappers.<OrderOperationLogDo>lambdaQuery()
                            .eq(OrderOperationLogDo::getOrderId, expireOrderId)
                            .orderByAsc(OrderOperationLogDo::getId));
            assertEquals(2, expireOrderLogs.size(), "应包含CREATE和CLOSE两条操作流水");
            assertEquals(OrderOperationAction.CREATE, expireOrderLogs.get(0).getAction());
            assertEquals(OrderOperationAction.CLOSE, expireOrderLogs.get(1).getAction());
            assertEquals(0L, expireOrderLogs.get(1).getOperatorId(), "系统自动关闭操作者应为0L");
            assertEquals(OrderStatus.PENDING_PAYMENT, expireOrderLogs.get(1).getFromStatus());
            assertEquals(OrderStatus.CLOSED, expireOrderLogs.get(1).getToStatus());

            // =========================================================================
            // 场景 B：真实 HTTP 下单（购买 3 件）-> 立即调用 HTTP 支付 -> 2秒后延迟消息到达应幂等跳过
            // =========================================================================
            String createJson2 = """
                    {
                      "requestId": "%s",
                      "items": [
                        {"productId": %d, "quantity": 3}
                      ]
                    }
                    """.formatted(UUID.randomUUID().toString(), productId);

            MvcResult createResult2 = mvc.perform(post("/orders/create")
                            .with(authentication(buyerPrincipal))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createJson2))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value(0))
                    .andReturn();

            Long paidOrderId = objectMapper.readTree(createResult2.getResponse().getContentAsString())
                    .path("data").path("id").asLong();
            createdOrderIds.add(paidOrderId);
            assertEquals(7, currentStock(productId), "购买3件后库存应从10扣减为7");

            // 在 2 秒到期前立即发起真实 HTTP 支付
            mvc.perform(post("/orders/{orderId}/pay", paidOrderId)
                            .with(authentication(buyerPrincipal)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.status").value("PAID"));

            // 等待 3.5 秒，让这笔订单的延迟关单消息到期并被 OrderCloseConsumer 消费
            TimeUnit.MILLISECONDS.sleep(3500);

            // 验证幂等跳过：已支付订单绝不被误关，库存绝不被退回，流水只有 CREATE + PAY
            mvc.perform(get("/orders/{orderId}", paidOrderId)
                            .with(authentication(buyerPrincipal)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.status").value("PAID"));

            assertEquals(7, currentStock(productId), "已支付订单被MQ幂等跳过后，库存必须保持为7，绝不能回补");

            List<OrderOperationLogDo> paidOrderLogs = operationLogMapper.selectList(
                    Wrappers.<OrderOperationLogDo>lambdaQuery()
                            .eq(OrderOperationLogDo::getOrderId, paidOrderId)
                            .orderByAsc(OrderOperationLogDo::getId));
            assertEquals(2, paidOrderLogs.size(), "已支付订单只应有CREATE和PAY两条流水，不应产生CLOSE流水");
            assertEquals(OrderOperationAction.CREATE, paidOrderLogs.get(0).getAction());
            assertEquals(OrderOperationAction.PAY, paidOrderLogs.get(1).getAction());

        } finally {
            // 清理本次测试产生的专用数据（按外键依赖顺序清理）
            for (Long orderId : createdOrderIds) {
                operationLogMapper.delete(Wrappers.<OrderOperationLogDo>lambdaQuery().eq(OrderOperationLogDo::getOrderId, orderId));
                orderItemMapper.delete(Wrappers.<OrderItemDo>lambdaQuery().eq(OrderItemDo::getOrderId, orderId));
                orderMapper.deleteById(orderId);
            }
            if (productId != null) {
                inventoryMapper.delete(Wrappers.<ProductInventoryDo>lambdaQuery().eq(ProductInventoryDo::getProductId, productId));
                productMapper.deleteById(productId);
            }
            if (buyerId != null) {
                userMapper.deleteById(buyerId);
            }
        }
    }

    private int currentStock(Long productId) {
        ProductInventoryDo inv = inventoryMapper.selectOne(
                Wrappers.<ProductInventoryDo>lambdaQuery().eq(ProductInventoryDo::getProductId, productId));
        assertNotNull(inv);
        return inv.getStock();
    }
}
