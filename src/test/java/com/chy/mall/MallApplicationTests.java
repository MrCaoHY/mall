package com.chy.mall;

import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class MallApplicationTests {
    // 注入真实Mapper，查询会访问配置的MySQL，而不是使用Mock。
    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductInventoryMapper productInventoryMapper;

    @Test
    void databaseReadWorks() {
        // null表示不附加查询条件；这里只读两张表，不新增或删除数据。
        Long productCount = productMapper.selectCount(null);
        Long inventoryCount = productInventoryMapper.selectCount(null);

        // 先检查非空，再比较数值，避免Long自动拆箱时出现空指针异常。
        assertNotNull(productCount, "商品数量查询不应返回null");
        assertNotNull(inventoryCount, "库存记录数量查询不应返回null");

        // 空表的数量为0，也应通过测试，不要求数据库已有商品。
        assertTrue(productCount >= 0, "商品数量不能为负数");
        assertTrue(inventoryCount >= 0, "库存记录数量不能为负数");

        System.out.printf("商品数量：%d，库存记录数量：%d%n", productCount, inventoryCount);
    }

}
