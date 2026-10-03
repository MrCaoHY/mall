package com.chy.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chy.mall.entity.OrderDo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单主表数据访问接口，继承 MyBatis-Plus 的基础增删改查方法。
 */
@Mapper
public interface OrderMapper extends BaseMapper<OrderDo> {
}
