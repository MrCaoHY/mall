package com.chy.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chy.mall.entity.UserDo;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表数据访问，常规 CRUD 使用 MyBatis-Plus。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserDo> {
}
