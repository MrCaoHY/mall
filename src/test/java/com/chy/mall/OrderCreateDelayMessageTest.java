package com.chy.mall;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.chy.mall.config.RabbitMqConfig;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.OrderStatus;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.mapper.OrderItemMapper;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.service.impl.OrderCreateTransactionService;
import com.chy.mall.service.impl.OrderOperationLogService;
import com.chy.mall.vo.OrderVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 订单创建延迟消息发送端单元测试：
 * 验证订单创建成功后向RabbitMQ延迟交换机发送带expiration的延迟关单消息，
 * 以及MQ发送异常时降级日志兜底、不破坏已提交订单。
 */
class OrderCreateDelayMessageTest {

    private static final Long BUYER_ID = 1L;
    private static final String REQUEST_ID = "req-delay-001";
    private static final String FINGERPRINT = "fp-delay-001";

    @BeforeAll
    static void initMybatis() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "unit-test-inventory");
        assistant.setCurrentNamespace(ProductInventoryMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, ProductInventoryDo.class);
    }

    @Test
    @DisplayName("订单创建成功：向延迟交换机正确投递包含orderId及expiration的关单延迟消息")
    void createNewOrder_SendsDelayCloseMessageSuccessfully() {
        ProductInventoryMapper inventoryMapper = mock(ProductInventoryMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        OrderOperationLogService operationLogService = mock(OrderOperationLogService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

        OrderCreateTransactionService service = new OrderCreateTransactionService(
                inventoryMapper, productMapper, orderMapper, orderItemMapper, operationLogService, rabbitTemplate);

        ProductDo product = new ProductDo();
        product.setId(10L);
        product.setSku("SKU-10");
        product.setName("测试商品");
        product.setPrice(new BigDecimal("99.00"));
        product.setStatus(ProductStatus.ON_SALE);

        when(productMapper.selectByIds(anyCollection())).thenReturn(List.of(product));
        when(inventoryMapper.update(isNull(), any())).thenReturn(1);
        when(orderMapper.insert(any(OrderDo.class))).thenAnswer(inv -> {
            OrderDo o = inv.getArgument(0);
            o.setId(8888L);
            o.setStatus(OrderStatus.PENDING_PAYMENT);
            return 1;
        });
        when(orderItemMapper.batchInsert(anyList())).thenReturn(1);

        TreeMap<Long, Integer> productMap = new TreeMap<>();
        productMap.put(10L, 1);

        OrderVo orderVo = service.createNewOrder(BUYER_ID, REQUEST_ID, FINGERPRINT, productMap);

        assertNotNull(orderVo);
        assertEquals(8888L, orderVo.getId());

        // 验证向延迟交换机发送了 orderId=8888L 的消息，并验证 MessagePostProcessor 设置了 expiration
        ArgumentCaptor<MessagePostProcessor> processorCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate, times(1)).convertAndSend(
                eq(RabbitMqConfig.ORDER_DELAY_EXCHANGE),
                eq(RabbitMqConfig.ORDER_DELAY_ROUTING_KEY),
                eq(8888L),
                processorCaptor.capture()
        );

        Message dummyMessage = new Message(new byte[0], new MessageProperties());
        processorCaptor.getValue().postProcessMessage(dummyMessage);
        assertEquals("1800000", dummyMessage.getMessageProperties().getExpiration());
    }

    @Test
    @DisplayName("MQ发送异常：降级日志记录，不抛出异常，已创建订单仍然正常返回")
    void createNewOrder_MqSendThrowsException_DegradesGracefullyWithoutFailingOrder() {
        ProductInventoryMapper inventoryMapper = mock(ProductInventoryMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        OrderMapper orderMapper = mock(OrderMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        OrderOperationLogService operationLogService = mock(OrderOperationLogService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

        OrderCreateTransactionService service = new OrderCreateTransactionService(
                inventoryMapper, productMapper, orderMapper, orderItemMapper, operationLogService, rabbitTemplate);

        ProductDo product = new ProductDo();
        product.setId(20L);
        product.setSku("SKU-20");
        product.setName("测试商品2");
        product.setPrice(new BigDecimal("19.90"));
        product.setStatus(ProductStatus.ON_SALE);

        when(productMapper.selectByIds(anyCollection())).thenReturn(List.of(product));
        when(inventoryMapper.update(isNull(), any())).thenReturn(1);
        when(orderMapper.insert(any(OrderDo.class))).thenAnswer(inv -> {
            OrderDo o = inv.getArgument(0);
            o.setId(9999L);
            o.setStatus(OrderStatus.PENDING_PAYMENT);
            return 1;
        });
        when(orderItemMapper.batchInsert(anyList())).thenReturn(1);

        // 模拟RabbitMQ服务不可用抛异常
        doThrow(new RuntimeException("RabbitMQ Broker Connection Refused"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class));

        TreeMap<Long, Integer> productMap = new TreeMap<>();
        productMap.put(20L, 1);

        // 核心断言：即使MQ发失败，下单业务绝对不能抛异常！
        OrderVo orderVo = service.createNewOrder(BUYER_ID, "req-fail-001", "fp-fail-001", productMap);

        assertNotNull(orderVo);
        assertEquals(9999L, orderVo.getId());
        verify(rabbitTemplate, times(1)).convertAndSend(anyString(), anyString(), eq(9999L), any(MessagePostProcessor.class));
    }
}
