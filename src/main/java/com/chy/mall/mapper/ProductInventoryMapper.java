package com.chy.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chy.mall.entity.ProductInventoryDo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 库存数据访问；业务层使用带条件的原子增减，避免先读后写覆盖并发结果。
 */
@Mapper
public interface  ProductInventoryMapper extends BaseMapper<ProductInventoryDo> {
}
