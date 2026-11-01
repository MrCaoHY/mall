package com.chy.mall.service;

import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.dto.OrderPageQuery;
import com.chy.mall.vo.OrderVo;
import com.chy.mall.vo.PageVo;
import com.chy.mall.vo.OrderOperationLogVo;

import java.util.List;

/**
 * 订单业务契约：统一校验可信买家ID，非法身份返回BUYER_IDENTITY_INVALID。
 * 下单识别请求意图，查询/状态变更均限制订单归属；外部调用方不得自行指定他人身份。
 */
public interface OrderService {
    OrderVo create(Long buyerId, CreateOrderRequest request);

    /** 取消当前买家的订单，重复取消不重复恢复库存。 */
    OrderVo cancel(Long buyerId, Long orderId);

    /** 支付自己的订单，已支付重试不改首次时间或重复记录流水。 */
    OrderVo pay(Long buyerId, Long orderId);

    /** 仅查询当前买家订单的成功操作记录。 */
    List<OrderOperationLogVo> listOperations(Long buyerId, Long orderId);

    /** 按当前可信买家及可选状态分页，包含当前页订单明细。 */
    PageVo<OrderVo> page(Long buyerId, OrderPageQuery query);

    /** 查询自己的单笔订单详情，非本人订单与不存在的订单统一返回未找到。 */
    OrderVo findById(Long buyerId, Long orderId);
}
