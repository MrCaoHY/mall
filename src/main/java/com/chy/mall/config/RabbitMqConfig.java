package com.chy.mall.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class RabbitMqConfig {
    public static final String ORDER_DELAY_EXCHANGE = "order.delay.exchange";
    public static final String ORDER_DELAY_QUEUE = "order.delay.queue";
    public static final String ORDER_DELAY_ROUTING_KEY = "order.delay.key";
    public static final String ORDER_CLOSE_EXCHANGE = "order.close.exchange";
    public static final String ORDER_CLOSE_QUEUE = "order.close.queue";
    public static final String ORDER_CLOSE_ROUTING_KEY = "order.close.key";
    /** 队列级最大兜底TTL（默认30分钟=1800000ms）；单笔订单实际延迟由消息expiration属性（mall.order.close-delay-ms）控制，避免修改配置时触发Broker队列参数冲突。 */
    @Value("${mall.order.queue-max-ttl-ms:1800000}")
    private long orderQueueMaxTtlMs;

    //死信交换机
    @Bean
    public DirectExchange orderCloseExchange() {
        return new DirectExchange(ORDER_CLOSE_EXCHANGE, true, false);
    }

    //关单消费队列
    @Bean
    public Queue orderCloseQueue() {
        return QueueBuilder.durable(ORDER_CLOSE_QUEUE).build();
    }

    //绑定队列到交换机
    @Bean
    public Binding orderCloseBinding() {
        return BindingBuilder.bind(orderCloseQueue()).to(orderCloseExchange()).with(ORDER_CLOSE_ROUTING_KEY);
    }

    //延迟交换机，未到期的订单队列存在这里
    @Bean
    public DirectExchange orderDelayExchange() {
        return new DirectExchange(ORDER_DELAY_EXCHANGE, true, false);
    }

    /**
     * 延迟队列(无人监听)：配置ttl和死信投递目标
     * 倒计时结束后 broker自动将消息头题到死信交换机
     */
    @Bean
    public Queue orderDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", ORDER_CLOSE_EXCHANGE);
        args.put("x-dead-letter-routing-key", ORDER_CLOSE_ROUTING_KEY);
        args.put("x-message-ttl", orderQueueMaxTtlMs);
        return QueueBuilder.durable(ORDER_DELAY_QUEUE).withArguments(args).build();
    }

    //绑定延迟队列到延迟交换机
    @Bean
    public Binding orderDelayBinding() {
        return BindingBuilder.bind(orderDelayQueue()).to(orderDelayExchange()).with(ORDER_DELAY_ROUTING_KEY);
    }

    //json消息序列化转换器,复用是spring容器中的ObjectMapper
    @Bean
    public MessageConverter JacksonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

}
