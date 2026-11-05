package com.chy.mall;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.config.RabbitMqConfig;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.entity.OrderItemDo;
import com.chy.mall.entity.OrderOperationLogDo;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.OrderStatus;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.mapper.OrderItemMapper;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.mapper.OrderOperationLogMapper;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * RabbitMQ TTL+DLX 延迟关单真实端到端集成测试：
 * 验证订单写入数据库后，向延迟交换机发送带有短TTL（1500ms）的消息，
 * 等待死信路由投递至关单队列，OrderCloseConsumer消费后成功将订单置为CLOSED并恢复库存。
 */
@SpringBootTest(properties = {
        "mall.order.close-schedule.enabled=false"
})
@EnabledIfSystemProperty(named = "mall.order.delay.enabled", matches = "true")
class OrderDelayCloseIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductInventoryMapper inventoryMapper;

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
    @DisplayName("端到端验证：消息在TTL到期后成为死信投递至关单队列，消费者自动关闭订单并恢复库存")
    void testDelayCloseEndToEnd() throws InterruptedException {
        // 1. 准备专用测试商品与库存 (初始库存 10)
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ProductDo product = new ProductDo();
        product.setSku("MQ-" + suffix);
        product.setName("MQ测试商品-" + suffix);
        product.setPrice(new BigDecimal("88.00"));
        product.setStatus(ProductStatus.ON_SALE);
        product.setCreatedAt(LocalDateTime.now());
        product.setUpdatedAt(LocalDateTime.now());
        productMapper.insert(product);
        Long productId = product.getId();

        ProductInventoryDo inventory = new ProductInventoryDo();
        inventory.setProductId(productId);
        inventory.setStock(10);
        inventoryMapper.insert(inventory);

        // 2. 扣减库存准备待支付订单 (购买2件，剩余8件)
        inventoryMapper.update(null, Wrappers.<ProductInventoryDo>lambdaUpdate()
                .eq(ProductInventoryDo::getProductId, productId)
                .setDecrBy(ProductInventoryDo::getStock, 2));

        OrderDo order = new OrderDo();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setBuyerId(99999L);
        order.setRequestId("req-mq-" + suffix);
        order.setRequestFingerprint("fp-mq-" + suffix);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setTotalAmount(new BigDecimal("176.00"));
        order.setCreatedAt(LocalDateTime.now().minusMinutes(31));
        order.setExpiresAt(LocalDateTime.now().minusMinutes(1)); // 已经满足到期时间
        order.setUpdatedAt(LocalDateTime.now().minusMinutes(31));
        orderMapper.insert(order);
        Long orderId = order.getId();

        OrderItemDo item = new OrderItemDo();
        item.setOrderId(orderId);
        item.setProductId(productId);
        item.setSkuSnapshot(product.getSku());
        item.setNameSnapshot(product.getName());
        item.setUnitPrice(product.getPrice());
        item.setQuantity(2);
        item.setLineAmount(new BigDecimal("176.00"));
        orderItemMapper.insert(item);

        try {
            // 3. 向延迟队列发送消息，设置单条消息 TTL = 1500ms
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.ORDER_DELAY_EXCHANGE,
                    RabbitMqConfig.ORDER_DELAY_ROUTING_KEY,
                    orderId,
                    msg -> {
                        msg.getMessageProperties().setExpiration("1500");
                        return msg;
                    }
            );

            // 4. 等待 3 秒（留出死信转换与消费缓冲时间）
            TimeUnit.MILLISECONDS.sleep(3000);

            // 5. 校验数据库：订单状态已自动变为 CLOSED
            OrderDo updatedOrder = orderMapper.selectById(orderId);
            assertNotNull(updatedOrder);
            assertEquals(OrderStatus.CLOSED, updatedOrder.getStatus(), "订单应被RabbitMQ延迟死信消费者成功关闭");

            // 6. 校验库存：已由 8 恢复为 10
            ProductInventoryDo restoredInventory = inventoryMapper.selectOne(
                    Wrappers.<ProductInventoryDo>lambdaQuery().eq(ProductInventoryDo::getProductId, productId));
            assertNotNull(restoredInventory);
            assertEquals(10, restoredInventory.getStock(), "库存应被自动恢复");

        } finally {
            // 7. 清理专用测试数据（先清外键子表，再清主表）
            operationLogMapper.delete(Wrappers.<OrderOperationLogDo>lambdaQuery().eq(OrderOperationLogDo::getOrderId, orderId));
            orderItemMapper.delete(Wrappers.<OrderItemDo>lambdaQuery().eq(OrderItemDo::getOrderId, orderId));
            orderMapper.deleteById(orderId);
            inventoryMapper.delete(Wrappers.<ProductInventoryDo>lambdaQuery().eq(ProductInventoryDo::getProductId, productId));
            productMapper.deleteById(productId);
        }
    }
}
