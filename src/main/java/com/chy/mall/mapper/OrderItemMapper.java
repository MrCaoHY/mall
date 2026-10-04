package com.chy.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chy.mall.entity.OrderItemDo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单明细数据访问接口，继承 MyBatis-Plus 的基础增删改查方法。
 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItemDo> {

    @Insert("""
        <script>
        INSERT INTO order_items
            (order_id, product_id, sku_snapshot, name_snapshot, unit_price, quantity, line_amount)
        VALUES
        <foreach collection="items" item="item" separator=",">
            (#{item.orderId}, #{item.productId}, #{item.skuSnapshot}, #{item.nameSnapshot},
             #{item.unitPrice}, #{item.quantity}, #{item.lineAmount})
        </foreach>
        </script>
    """)
    int batchInsert(@Param("items") List<OrderItemDo> items);
}
