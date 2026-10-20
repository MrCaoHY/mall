package com.chy.mall.service;

import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.vo.OrderVo;

/**
 * 订单业务契约：下单识别买家请求意图，取消时核对订单归属并释放库存一次。
 */
public interface OrderService {
    OrderVo create(Long buyerId, CreateOrderRequest request);

    /** 取消当前买家的订单，重复取消不重复恢复库存。 */
    OrderVo cancel(Long buyerId, Long orderId);

    OrderVo pay(Long buyerId, Long orderId);
}
