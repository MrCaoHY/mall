package com.chy.mall;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 加载真实应用：不Mock Service或Mapper，数据库连接来自application.yaml和环境变量。
@SpringBootTest
@AutoConfigureMockMvc
// 当前同步请求与Service共用测试事务，测试结束默认回滚，不保留测试商品和库存。
// 这是测试数据清理机制，不代表已经验证Service自身的失败回滚或独立提交。
@Transactional
class ProductControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductInventoryMapper productInventoryMapper;

    @Test
    // 模拟登录身份，不需要真实密码；这不是生产认证或ADMIN权限规则的验收。
    @WithMockUser(username = "api-test-admin", roles = "ADMIN")
    void createProductReturns201AndSavesInventory() throws Exception {
        // SKU共32个字符，每次生成新值，避免与已有商品重复。
        String sku = "API-" + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
        String requestBody = """
                {
                  "sku": "%s",
                  "name": "Java入门书",
                  "price": 59.90,
                  "initialStock": 20
                }
                """.formatted(sku);

        // MockMvc在进程内经过MVC和安全过滤器处理请求，不连接8081端口。
        // csrf()提供测试CSRF令牌，不关闭过滤器，也不修改生产权限配置。
        ResultActions response = mockMvc.perform(post("/products/create")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.sku").value(sku))
                .andExpect(jsonPath("$.data.name").value("Java入门书"))
                .andExpect(jsonPath("$.data.price").value(59.90))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"))
                .andExpect(jsonPath("$.data.stock").value(20));

        // 不只检查响应：从真实数据库读回商品，核对保存的字段。
        ProductDo savedProduct = productMapper.selectOne(Wrappers.<ProductDo>lambdaQuery()
                .eq(ProductDo::getSku, sku));
        assertNotNull(savedProduct, "商品应该已经插入数据库");
        assertNotNull(savedProduct.getId(), "商品应该获得数据库生成的ID");
        assertEquals(sku, savedProduct.getSku());
        assertEquals("Java入门书", savedProduct.getName());
        assertNotNull(savedProduct.getPrice(), "数据库中的商品价格不能为空");
        // compareTo比较金额数值，不受59.9和59.90的小数位差异影响。
        assertEquals(0, new BigDecimal("59.90").compareTo(savedProduct.getPrice()));
        assertEquals(ProductStatus.ON_SALE, savedProduct.getStatus());

        // 库存表主键就是商品ID：同时核对关联关系和初始库存。
        ProductInventoryDo savedInventory = productInventoryMapper.selectById(savedProduct.getId());
        assertNotNull(savedInventory, "商品对应的库存记录应该已经插入数据库");
        assertEquals(savedProduct.getId(), savedInventory.getProductId());
        assertEquals(Integer.valueOf(20), savedInventory.getStock());

        // 将JSON中的ID按Long比较，确认响应ID与数据库自增ID一致。
        response.andExpect(jsonPath("$.data.id").value(is(savedProduct.getId()), Long.class));

        System.out.printf("接口创建成功，商品ID：%d，SKU：%s，库存：%d（测试结束回滚）%n",
                savedProduct.getId(), sku, savedInventory.getStock());
    }

    @Test
    @WithMockUser(username = "api-test-admin", roles = "ADMIN")
    void getProductReturns200WithActualStatusAndZeroStock() throws Exception {
        ProductDo product = insertProductFixture();
        ProductInventoryDo inventory = new ProductInventoryDo();
        inventory.setProductId(product.getId());
        inventory.setStock(0);
        assertEquals(1, productInventoryMapper.insert(inventory), "应该插入一条测试库存记录");

        // 下架商品与零库存仍返回真实详情，不隐藏状态或改变库存值。
        mockMvc.perform(get("/products/{id}", product.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(is(product.getId()), Long.class))
                .andExpect(jsonPath("$.data.sku").value(product.getSku()))
                .andExpect(jsonPath("$.data.name").value(product.getName()))
                .andExpect(jsonPath("$.data.price").value(123.45))
                .andExpect(jsonPath("$.data.status").value("OFF_SALE"))
                .andExpect(jsonPath("$.data.stock").value(0));
    }

    @Test
    @WithMockUser(username = "api-test-admin", roles = "ADMIN")
    void getMissingProductReturns404() throws Exception {
        ProductDo product = insertProductFixture();
        // 只删除本测试刚插入且没有库存的商品，获得确定不存在的ID。
        assertEquals(1, productMapper.deleteById(product.getId()), "应该删除本测试的一条商品记录");

        mockMvc.perform(get("/products/{id}", product.getId()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.message").value("商品不存在"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @WithMockUser(username = "api-test-admin", roles = "ADMIN")
    void getProductWithoutInventoryReturns500() throws Exception {
        ProductDo product = insertProductFixture();

        // 故意不插入库存；这个用例产生一条库存缺失错误日志是预期行为。

        mockMvc.perform(get("/products/{id}", product.getId()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("商品库存数据异常"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @WithMockUser(username = "api-test-admin", roles = "ADMIN")
    void getProductWithZeroIdReturns400() throws Exception {
        mockMvc.perform(get("/products/{id}", 0))
                .andExpect(status().isBadRequest());
    }

    private ProductDo insertProductFixture() {
        ProductDo product = new ProductDo();
        // SKU共32个字符，测试之间及与已有商品之间不共用固定值。
        product.setSku("API-" + UUID.randomUUID().toString().replace("-", "").substring(0, 28));
        product.setName("商品详情测试");
        product.setPrice(new BigDecimal("123.45"));
        product.setStatus(ProductStatus.OFF_SALE);
        assertEquals(1, productMapper.insert(product), "应该插入一条测试商品记录");
        assertNotNull(product.getId(), "测试商品应该获得数据库生成的ID");
        return product;
    }
}
