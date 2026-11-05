package com.chy.mall;

import com.chy.mall.consumer.OrderCloseConsumer;
import com.chy.mall.service.impl.OrderCloseTransactionService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 订单超时关单消费者单元测试：
 * 验证手动ACK、幂等安全跳过、毒药消息过滤、首次异常重试及二次异常拒绝防死锁逻辑。
 */
class OrderCloseConsumerTest {

    private OrderCloseTransactionService orderCloseTransactionService;
    private Channel channel;
    private Message message;
    private MessageProperties messageProperties;
    private OrderCloseConsumer consumer;

    @BeforeEach
    void setUp() {
        orderCloseTransactionService = mock(OrderCloseTransactionService.class);
        channel = mock(Channel.class);
        message = mock(Message.class);
        messageProperties = new MessageProperties();
        when(message.getMessageProperties()).thenReturn(messageProperties);

        consumer = new OrderCloseConsumer(orderCloseTransactionService);
    }

    @Test
    @DisplayName("正常到期待支付订单：成功关闭并手动ACK签收")
    void onOrderCloseMessage_ValidPendingOrder_ClosesAndAcksSuccessfully() throws IOException {
        long deliveryTag = 101L;
        long orderId = 1001L;
        messageProperties.setDeliveryTag(deliveryTag);
        when(orderCloseTransactionService.closeExpiredOrder(orderId)).thenReturn(true);

        consumer.onOrderCloseMessage(orderId, message, channel);

        // 验证执行了关单，并给Broker发送了单条basicAck
        verify(orderCloseTransactionService, times(1)).closeExpiredOrder(orderId);
        verify(channel, times(1)).basicAck(deliveryTag, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("已支付/已取消订单（幂等跳过）：不改数据但仍手动ACK完成消息生命周期")
    void onOrderCloseMessage_AlreadyPaidOrder_SkipsAndAcksSuccessfully() throws IOException {
        long deliveryTag = 102L;
        long orderId = 1002L;
        messageProperties.setDeliveryTag(deliveryTag);
        // 状态不符条件更新返回0行，关单服务返回false
        when(orderCloseTransactionService.closeExpiredOrder(orderId)).thenReturn(false);

        consumer.onOrderCloseMessage(orderId, message, channel);

        // 验证关单被跳过后，依然正常签收消息，避免消息重复留在队列
        verify(orderCloseTransactionService, times(1)).closeExpiredOrder(orderId);
        verify(channel, times(1)).basicAck(deliveryTag, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("毒药消息（空ID或负数）：直接ACK丢弃，不调用底层关单服务，防死循环")
    void onOrderCloseMessage_PoisonPillMessage_AcksAndDiscardsWithoutInvokingService() throws IOException {
        long deliveryTag = 103L;
        messageProperties.setDeliveryTag(deliveryTag);

        // 1. null 订单ID
        consumer.onOrderCloseMessage(null, message, channel);
        // 2. 0 订单ID
        consumer.onOrderCloseMessage(0L, message, channel);
        // 3. 负数订单ID
        consumer.onOrderCloseMessage(-99L, message, channel);

        // 验证绝对不触碰业务Service
        verify(orderCloseTransactionService, never()).closeExpiredOrder(anyLong());
        // 验证3次非法消息全部被直接Ack丢弃
        verify(channel, times(3)).basicAck(deliveryTag, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("首次系统异常（redelivered=false）：执行basicNack并requeue重新入队一次")
    void onOrderCloseMessage_FirstFailure_NacksWithRequeue() throws IOException {
        long deliveryTag = 104L;
        long orderId = 1004L;
        messageProperties.setDeliveryTag(deliveryTag);
        messageProperties.setRedelivered(false);
        when(orderCloseTransactionService.closeExpiredOrder(orderId))
                .thenThrow(new RuntimeException("数据库连接瞬时超时"));

        consumer.onOrderCloseMessage(orderId, message, channel);

        // 验证首次故障放回队列重入队
        verify(channel, times(1)).basicNack(deliveryTag, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("二次重试异常（redelivered=true）：执行basicReject不再入队，防重试风暴，交由定时扫描兜底")
    void onOrderCloseMessage_SecondFailure_RejectsWithoutRequeue() throws IOException {
        long deliveryTag = 105L;
        long orderId = 1005L;
        messageProperties.setDeliveryTag(deliveryTag);
        messageProperties.setRedelivered(true);
        when(orderCloseTransactionService.closeExpiredOrder(orderId))
                .thenThrow(new RuntimeException("死锁异常"));

        consumer.onOrderCloseMessage(orderId, message, channel);

        // 验证不再requeue，直接移出队列
        verify(channel, times(1)).basicReject(deliveryTag, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }
}
