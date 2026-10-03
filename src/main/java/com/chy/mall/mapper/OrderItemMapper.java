package com.chy.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chy.mall.entity.OrderItemDo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单明细数据访问接口，继承 MyBatis-Plus 的基础增删改查方法。
 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItemDo> {
}
