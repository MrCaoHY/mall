package com.chy.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chy.mall.entity.ProductDo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品数据访问，继承MyBatis-Plus的基础CRUD，不在Mapper记录请求或实体内容。
 */
@Mapper
public interface ProductMapper extends BaseMapper<ProductDo> {
}
