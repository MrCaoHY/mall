package com.chy.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
@Data
@TableName("product_inventory")
public class ProductInventoryDo {
    @TableId(value = "product_id", type = IdType.INPUT)
    private Long productId;
    private Integer stock;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
