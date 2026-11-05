package com.chy.mall.consumer;

import com.chy.mall.config.RabbitMqConfig;
import com.chy.mall.service.impl.OrderCloseTransactionService;
import com.chy.mall.util.LogSupport;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
@RequiredArgsConstructor
public class OrderCloseConsumer {
    private final OrderCloseTransactionService orderCloseTransactionService;

    @RabbitListener(queues = RabbitMqConfig.ORDER_CLOSE_QUEUE)
    public void onOrderCloseMessage(Long orderId, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        log.info("event=order_close_message_received orderId={} tag={}", orderId, deliveryTag);
        try {
            // 1. 防毒校验：非法报文直接签收丢弃，防止坏消息死循环打爆队列
            if (orderId == null || orderId <= 0) {
                log.warn("event=order_close_invalid_message orderId={}", orderId);
                channel.basicAck(deliveryTag, false);
                return;
            }
            // 2. 调用单笔关闭事务（基于状态机条件更新幂等执行）
            boolean closed = orderCloseTransactionService.closeExpiredOrder(orderId);
            if (closed) {
                log.info("event=order_closed_by_mq orderId={}", orderId);
            }else{
                log.info("event=order_close_skipped_by_mq orderId={}", orderId);
            }
            // 3. 无论是成功关单还是安全跳过，均属于正常业务处理完成，手动 ACK
            channel.basicAck(deliveryTag, false);
        } catch (Exception  e) {
            log.error("event=order_close_consume_failed orderId={} error={}", orderId, LogSupport.safeStackTrace(e));
            // 4. 故障保护：已经重试过的消息拒绝重新入队（交由数据库定时扫描兜底），首遇故障可重入一次
            boolean redelivered = Boolean.TRUE.equals(message.getMessageProperties().getRedelivered());
            if (redelivered) {
                log.warn("event=order_close_message_rejected_after_retry orderId={}", orderId);
                channel.basicReject(deliveryTag, false);
            } else {
                channel.basicNack(deliveryTag, false, true);
            }
        }
    }
}