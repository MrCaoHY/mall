package com.chy.mall;

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
import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 使用真实 Service、Mapper 和数据库；每次只创建并查询唯一 marker 对应的测试商品。
@SpringBootTest
@AutoConfigureMockMvc
// 同步 MockMvc 请求共用测试事务，结束时回滚 fixture；不清空表、不修改已有商品。
@Transactional
// 模拟合法身份以验收分页功能，不代表验证了生产认证流程或权限规则。
@WithMockUser(username = "api-test-admin", roles = "ADMIN")
class ProductPageControllerTests {

    private static final BigDecimal PRICE = new BigDecimal("123.45");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductInventoryMapper productInventoryMapper;

    @Test
    void sameCreatedAtUsesDescendingIdAcrossPagesAndReturnsActualStock() throws Exception {
        String marker = newMarker();
        ProductDo first = insertProduct(marker + " first", randomSku(), ProductStatus.ON_SALE, 0);
        ProductDo second = insertProduct(marker + " second", randomSku(), ProductStatus.OFF_SALE, 7);
        ProductDo third = insertProduct(marker + " third", randomSku(), ProductStatus.ON_SALE, 19);

        ResultActions firstPage = mockMvc.perform(get("/products/page")
                .param("keyword", marker).param("page", "1").param("size", "2"));
        expectPage(firstPage, 1, 2, 3, 2);
        expectRecord(firstPage, 0, third, 19);
        expectRecord(firstPage, 1, second, 7);

        ResultActions secondPage = mockMvc.perform(get("/products/page")
                .param("keyword", marker).param("page", "2").param("size", "2"));
        expectPage(secondPage, 2, 2, 3, 1);
        expectRecord(secondPage, 0, first, 0);
    }

    @Test
    void createdAtTakesPriorityOverDescendingId() throws Exception {
        String marker = newMarker();
        ProductDo newer = insertProduct(marker + " newer", randomSku(), ProductStatus.ON_SALE,
                2, CREATED_AT.plusDays(1));
        // 后插入的记录 ID 更大，但创建时间更早，应该排在后面。
        ProductDo older = insertProduct(marker + " older", randomSku(), ProductStatus.ON_SALE,
                1, CREATED_AT);

        ResultActions response = mockMvc.perform(get("/products/page").param("keyword", marker));
        expectPage(response, 1, 10, 2, 2);
        expectRecord(response, 0, newer, 2);
        expectRecord(response, 1, older, 1);
    }

    @Test
    void combinesTrimmedKeywordStatusAndExactSkuWithAnd() throws Exception {
        String marker = newMarker();
        ProductDo namedFirst = insertProduct(marker + " Alpha", randomSku(), ProductStatus.ON_SALE, 3);
        ProductDo namedSecond = insertProduct(marker + " Beta", randomSku(), ProductStatus.ON_SALE, 5);
        ProductDo offSale = insertProduct(marker + " Gamma", randomSku(), ProductStatus.OFF_SALE, 8);
        insertProduct(newMarker() + " unrelated", randomSku(), ProductStatus.ON_SALE, 9);
        // marker 只出现在 SKU 中：覆盖 name OR sku 的另一分支。
        String matchingSku = marker + randomSku().substring(0, 32 - marker.length());
        ProductDo skuMatch = insertProduct(newMarker() + " SKU only", matchingSku,
                ProductStatus.ON_SALE, 11);

        ResultActions combined = mockMvc.perform(get("/products/page")
                .param("keyword", "  " + marker + "  ").param("status", "ON_SALE"));
        expectPage(combined, 1, 10, 3, 3);
        expectRecord(combined, 0, skuMatch, 11);
        expectRecord(combined, 1, namedSecond, 5);
        expectRecord(combined, 2, namedFirst, 3);

        ResultActions exact = mockMvc.perform(get("/products/page")
                .param("keyword", marker).param("status", "ON_SALE")
                .param("sku", namedFirst.getSku()));
        expectPage(exact, 1, 10, 1, 1);
        expectRecord(exact, 0, namedFirst, 3);

        // SKU 是精确匹配；只有前缀相同不算匹配。
        expectPage(mockMvc.perform(get("/products/page")
                .param("keyword", marker).param("sku", matchingSku.substring(0, 24))),
                1, 10, 0, 0);

        // SKU 和关键词都匹配，也必须同时满足状态。
        expectPage(mockMvc.perform(get("/products/page")
                .param("keyword", marker).param("sku", offSale.getSku()).param("status", "ON_SALE")),
                1, 10, 0, 0);
    }

    @Test
    void defaultsToFirstPageAndTenRecords() throws Exception {
        String marker = newMarker();
        ProductDo product = insertProduct(marker + " default", randomSku(), ProductStatus.ON_SALE, 4);

        ResultActions response = mockMvc.perform(get("/products/page").param("keyword", marker));
        expectPage(response, 1, 10, 1, 1);
        expectRecord(response, 0, product, 4);
    }

    @Test
    void returnsEmptyRecordsForNoMatchAndBeyondLastPageWithAccurateTotal() throws Exception {
        String marker = newMarker();
        insertProduct(marker + " first", randomSku(), ProductStatus.ON_SALE, 1);
        insertProduct(marker + " second", randomSku(), ProductStatus.ON_SALE, 2);

        expectPage(mockMvc.perform(get("/products/page")
                .param("keyword", newMarker()).param("page", "1").param("size", "5")),
                1, 5, 0, 0);
        expectPage(mockMvc.perform(get("/products/page")
                .param("keyword", marker).param("page", "2").param("size", "2")),
                2, 2, 2, 0);
    }

    @Test
    void rejectsInvalidPagingStatusAndOverlongFilters() throws Exception {
        String[][] invalidParameters = {
                {"page", "0"},
                {"size", "0"},
                {"size", "101"},
                {"status", "INVALID_STATUS"},
                {"keyword", "k".repeat(101)},
                {"sku", "s".repeat(33)}
        };
        for (String[] parameter : invalidParameters) {
            mockMvc.perform(get("/products/page").param(parameter[0], parameter[1]))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void returns500WhenSelectedProductHasNoInventory() throws Exception {
        String marker = newMarker();
        ProductDo product = insertProduct(marker + " missing inventory", randomSku(),
                ProductStatus.ON_SALE, null);

        // 故意不给本测试商品插入库存，预期产生一条库存缺失错误日志。
        mockMvc.perform(get("/products/page").param("keyword", marker).param("sku", product.getSku()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("商品库存数据异常"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void treatsPercentUnderscoreAndEscapeCharacterAsLiteralKeywordText() throws Exception {
        String marker = newMarker();
        String[] specialCharacters = {"%", "_", "!"};
        ProductDo[] products = new ProductDo[specialCharacters.length];
        for (int index = 0; index < specialCharacters.length; index++) {
            products[index] = insertProduct(marker + specialCharacters[index], randomSku(),
                    ProductStatus.ON_SALE, index);
        }
        // 若 % 或 _ 被当成通配符，这条记录及其他特殊字符记录也会错误匹配。
        insertProduct(marker + "X", randomSku(), ProductStatus.ON_SALE, 20);

        for (int index = 0; index < specialCharacters.length; index++) {
            ResultActions response = mockMvc.perform(get("/products/page")
                    .param("keyword", "  " + marker + specialCharacters[index] + "  "));
            expectPage(response, 1, 10, 1, 1);
            expectRecord(response, 0, products[index], index);
        }
    }

    @Test
    void treatsQuoteAndSqlLikeTextAsLiteralKeyword() throws Exception {
        String marker = newMarker();
        String literalKeyword = marker + " ' OR 1=1 --";
        ProductDo literal = insertProduct(literalKeyword, randomSku(), ProductStatus.ON_SALE, 6);
        insertProduct(marker + " ordinary", randomSku(), ProductStatus.ON_SALE, 7);

        ResultActions response = mockMvc.perform(get("/products/page")
                .param("keyword", "  " + literalKeyword + "  "));
        expectPage(response, 1, 10, 1, 1);
        expectRecord(response, 0, literal, 6);
    }

    private void expectPage(ResultActions response, int page, int size, long total, int recordCount)
            throws Exception {
        response.andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("SUCCESS"))
                .andExpect(jsonPath("$.data.page").value(page))
                .andExpect(jsonPath("$.data.size").value(size))
                .andExpect(jsonPath("$.data.total").value(is(total), Long.class))
                .andExpect(jsonPath("$.data.records").value(hasSize(recordCount)));
    }

    private void expectRecord(ResultActions response, int index, ProductDo product, int stock)
            throws Exception {
        String path = "$.data.records[" + index + "]";
        response.andExpect(jsonPath(path + ".id").value(is(product.getId()), Long.class))
                .andExpect(jsonPath(path + ".sku").value(product.getSku()))
                .andExpect(jsonPath(path + ".name").value(product.getName()))
                .andExpect(jsonPath(path + ".price").value(123.45))
                .andExpect(jsonPath(path + ".status").value(product.getStatus().name()))
                .andExpect(jsonPath(path + ".stock").value(stock));
    }

    private ProductDo insertProduct(String name, String sku, ProductStatus status, Integer stock) {
        return insertProduct(name, sku, status, stock, CREATED_AT);
    }

    private ProductDo insertProduct(String name, String sku, ProductStatus status, Integer stock,
                                    LocalDateTime createdAt) {
        assertEquals(32, sku.length(), "测试 SKU 应该恰好为32个字符");
        ProductDo product = new ProductDo();
        product.setSku(sku);
        product.setName(name);
        product.setPrice(PRICE);
        product.setStatus(status);
        product.setCreatedAt(createdAt);
        product.setUpdatedAt(createdAt);
        assertEquals(1, productMapper.insert(product), "应该插入一条本测试的商品记录");
        assertNotNull(product.getId(), "测试商品应该获得数据库生成的ID");

        if (stock != null) {
            ProductInventoryDo inventory = new ProductInventoryDo();
            inventory.setProductId(product.getId());
            inventory.setStock(stock);
            assertEquals(1, productInventoryMapper.insert(inventory), "应该插入一条本测试的库存记录");
        }
        return product;
    }

    private static String newMarker() {
        // 长度20，可同时用于唯一名称关键词和32字符SKU中的唯一前缀。
        return "PG" + randomSku().substring(0, 18);
    }

    private static String randomSku() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
