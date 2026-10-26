package com.chy.mall.service;

import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.vo.OrderVo;
import com.chy.mall.vo.OrderOperationLogVo;

import java.util.List;

/**
 * 订单业务契约：下单识别买家请求意图，取消时核对订单归属并释放库存一次。
 */
public interface OrderService {
    OrderVo create(Long buyerId, CreateOrderRequest request);

    /** 取消当前买家的订单，重复取消不重复恢复库存。 */
    OrderVo cancel(Long buyerId, Long orderId);

    /** 支付自己的订单，已支付重试不改首次时间或重复记录流水。 */
    OrderVo pay(Long buyerId, Long orderId);

    /** 仅查询当前买家订单的成功操作记录。 */
    List<OrderOperationLogVo> listOperations(Long buyerId, Long orderId);
}
