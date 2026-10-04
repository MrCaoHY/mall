package com.chy.mall.service;

import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.vo.OrderVo;

public interface OrderService {
    OrderVo create(Long buyerId, CreateOrderRequest request);
}
