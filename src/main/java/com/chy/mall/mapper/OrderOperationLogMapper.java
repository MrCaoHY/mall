package com.chy.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chy.mall.entity.OrderOperationLogDo;
import org.apache.ibatis.annotations.Mapper;

/** 操作流水数据访问，复用MyBatis-Plus，不新增手写业务SQL。 */
@Mapper
public interface OrderOperationLogMapper extends BaseMapper<OrderOperationLogDo> {
}
