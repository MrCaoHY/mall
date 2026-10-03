package com.chy.mall;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chy.mall.dto.ProductPageQuery;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.exception.ProductInventoryMissingException;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.service.impl.ProductServiceImpl;
import com.chy.mall.vo.PageVo;
import com.chy.mall.vo.ProductVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

// 只创建Service和两个Mapper的Mockito替身，不启动Spring，也不连接数据库。
class ProductPageServiceTests {
    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final ProductInventoryMapper inventoryMapper = mock(ProductInventoryMapper.class);
    private final ProductServiceImpl service = new ProductServiceImpl(productMapper, inventoryMapper);

    @BeforeAll
    static void initializeEntityMappings() {
        // Wrapper通过实体属性取得数据库列名；纯Mockito测试需初始化内存映射。
        // 此处只建立元数据，不启动Spring，也不连接数据库。
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant productAssistant = new MapperBuilderAssistant(configuration, "unit-test-products");
        productAssistant.setCurrentNamespace(ProductMapper.class.getName());
        TableInfoHelper.initTableInfo(productAssistant, ProductDo.class);
        MapperBuilderAssistant inventoryAssistant = new MapperBuilderAssistant(configuration, "unit-test-inventory");
        inventoryAssistant.setCurrentNamespace(ProductInventoryMapper.class.getName());
        TableInfoHelper.initTableInfo(inventoryAssistant, ProductInventoryDo.class);
    }

    @Test
    void pageJoinsUnorderedInventoriesByIdAndPreservesProductOrder() {
        ProductPageQuery query = query(2, 2);
        ProductDo first = product(31L, "SKU-FIRST", "第一件商品", "12.30", ProductStatus.OFF_SALE);
        ProductDo second = product(14L, "SKU-SECOND", "第二件商品", "98.76", ProductStatus.ON_SALE);
        stubPage(5L, List.of(first, second));
        // 故意倒置库存顺序，且下架商品的实际库存为0。
        when(inventoryMapper.selectList(anyInventoryWrapper()))
                .thenReturn(List.of(inventory(14L, 8), inventory(31L, 0)));

        PageVo<ProductVo> result = service.page(query);

        assertPage(result, 2, 2, 5L);
        assertEquals(2, result.getRecords().size());
        assertProduct(result.getRecords().get(0), first, 0);
        assertProduct(result.getRecords().get(1), second, 8);
        verifyProductPage(query);
        verify(inventoryMapper).selectList(anyInventoryWrapper());
        verify(inventoryMapper, never()).selectById(any(Long.class));
        // 验证两个Mapper各方法的实际调用次数，防止循环查库存造成N+1。
        verifyNoMoreInteractions(productMapper, inventoryMapper);
    }

    @Test
    void pageWithZeroTotalReturnsEmptyAndSkipsInventoryQuery() {
        ProductPageQuery query = query(1, 10);
        stubPage(0L, List.of());

        PageVo<ProductVo> result = service.page(query);

        assertPage(result, 1, 10, 0L);
        assertTrue(result.getRecords().isEmpty());
        verifyProductPage(query);
        verifyNoMoreInteractions(productMapper);
        verifyNoInteractions(inventoryMapper);
    }

    @Test
    void pagePastLastPageReturnsEmptyAndSkipsInventoryQuery() {
        ProductPageQuery query = query(3, 2);
        // 插件返回真实总数，但请求页码已经超过末页，因此商品列表为空。
        stubPage(4L, List.of());

        PageVo<ProductVo> result = service.page(query);

        assertPage(result, 3, 2, 4L);
        assertTrue(result.getRecords().isEmpty());
        verifyProductPage(query);
        verifyNoMoreInteractions(productMapper);
        verifyNoInteractions(inventoryMapper);
    }

    @Test
    void pageWithCountButNoRecordsSkipsInventoryQuery() {
        ProductPageQuery query = query(1, 10);
        // 即使分页结果有总数但没有记录，也不能对空商品集合执行IN查询。
        stubPage(1L, List.of());

        PageVo<ProductVo> result = service.page(query);

        assertPage(result, 1, 10, 1L);
        assertTrue(result.getRecords().isEmpty());
        verifyProductPage(query);
        verifyNoMoreInteractions(productMapper);
        verifyNoInteractions(inventoryMapper);
    }

    @Test
    void pageWithMissingInventoryReportsTheAffectedProductId() {
        ProductPageQuery query = query(1, 10);
        ProductDo first = product(31L, "SKU-FIRST", "第一件商品", "12.30", ProductStatus.ON_SALE);
        ProductDo missing = product(14L, "SKU-MISSING", "缺库存商品", "98.76", ProductStatus.OFF_SALE);
        stubPage(2L, List.of(first, missing));
        when(inventoryMapper.selectList(anyInventoryWrapper())).thenReturn(List.of(inventory(31L, 5)));

        ProductInventoryMissingException exception = assertThrows(
                ProductInventoryMissingException.class, () -> service.page(query));

        assertEquals(Long.valueOf(14L), exception.getProductId());
        verifyProductPage(query);
        verify(inventoryMapper).selectList(anyInventoryWrapper());
        verifyNoMoreInteractions(productMapper, inventoryMapper);
    }

    @Test
    void pageUsesLongOffsetForMaximumIntegerPage() {
        ProductPageQuery query = query(Integer.MAX_VALUE, 100);
        stubPage(Long.MAX_VALUE, List.of());

        PageVo<ProductVo> result = service.page(query);

        assertPage(result, Integer.MAX_VALUE, 100, Long.MAX_VALUE);
        assertTrue(result.getRecords().isEmpty());
        // Page使用long计算偏移，用明确期望值验证大页码不会int溢出。
        verify(productMapper).selectPage(argThat((Page<ProductDo> page) ->
                page.getCurrent() == Integer.MAX_VALUE && page.getSize() == 100
                        && page.offset() == 214_748_364_600L), anyProductWrapper());
        verifyNoMoreInteractions(productMapper);
        verifyNoInteractions(inventoryMapper);
    }

    @Test
    void queryDefaultsToFirstPageWithTenRecords() {
        ProductPageQuery query = new ProductPageQuery();

        assertEquals(Integer.valueOf(1), query.getPage());
        assertEquals(Integer.valueOf(10), query.getSize());
        assertNull(query.getKeywordPattern());
        assertNull(query.getSkuFilter());
    }

    @Test
    void queryTrimsFiltersAndEscapesLiteralLikeCharacters() {
        ProductPageQuery query = new ProductPageQuery();
        query.setKeyword("  sale!50%_book  ");
        query.setSku("  SKU-001  ");

        assertEquals("%sale!!50!%!_book%", query.getKeywordPattern());
        assertEquals("SKU-001", query.getSkuFilter());
    }

    @Test
    void queryTreatsWhitespaceOnlyFiltersAsAbsent() {
        ProductPageQuery query = new ProductPageQuery();
        query.setKeyword(" \t\n ");
        query.setSku(" \t\n ");

        assertNull(query.getKeywordPattern());
        assertNull(query.getSkuFilter());
    }

    // 模拟分页Mapper的返回，不在纯单元测试中执行分页插件或SQL。
    private void stubPage(long total, List<ProductDo> products) {
        when(productMapper.selectPage(anyProductPage(), anyProductWrapper())).thenAnswer(invocation -> {
            Page<ProductDo> page = invocation.getArgument(0);
            page.setTotal(total);
            page.setRecords(products);
            return page;
        });
    }

    private void verifyProductPage(ProductPageQuery query) {
        verify(productMapper).selectPage(argThat((Page<ProductDo> page) ->
                page.getCurrent() == query.getPage().longValue()
                        && page.getSize() == query.getSize().longValue()), anyProductWrapper());
    }

    private static Page<ProductDo> anyProductPage() {
        return org.mockito.ArgumentMatchers.<Page<ProductDo>>any();
    }

    private static Wrapper<ProductDo> anyProductWrapper() {
        return org.mockito.ArgumentMatchers.<Wrapper<ProductDo>>any();
    }

    // 显式指定Wrapper泛型，避免MyBatis-Plus selectList重载的类型歧义。
    private static Wrapper<ProductInventoryDo> anyInventoryWrapper() {
        return org.mockito.ArgumentMatchers.<Wrapper<ProductInventoryDo>>any();
    }

    private static ProductPageQuery query(int page, int size) {
        ProductPageQuery query = new ProductPageQuery();
        query.setPage(page);
        query.setSize(size);
        return query;
    }

    private static ProductDo product(long id, String sku, String name, String price, ProductStatus status) {
        ProductDo product = new ProductDo();
        product.setId(id);
        product.setSku(sku);
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        product.setStatus(status);
        return product;
    }

    private static ProductInventoryDo inventory(long productId, int stock) {
        ProductInventoryDo inventory = new ProductInventoryDo();
        inventory.setProductId(productId);
        inventory.setStock(stock);
        return inventory;
    }

    private static void assertPage(PageVo<ProductVo> result, int page, int size, long total) {
        assertEquals(Integer.valueOf(page), result.getPage());
        assertEquals(Integer.valueOf(size), result.getSize());
        assertEquals(Long.valueOf(total), result.getTotal());
    }

    private static void assertProduct(ProductVo actual, ProductDo expected, int stock) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getSku(), actual.getSku());
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getPrice(), actual.getPrice());
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(Integer.valueOf(stock), actual.getStock());
    }
}
