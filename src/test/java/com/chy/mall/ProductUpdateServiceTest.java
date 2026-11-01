package com.chy.mall;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.chy.mall.dto.UpdateProductRequest;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.service.impl.ProductServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只验证编辑入口的关键分支；实际字段落库和回滚由真实MySQL测试负责。 */
class ProductUpdateServiceTest {
    private final ProductMapper products = mock(ProductMapper.class);
    private final ProductInventoryMapper inventories = mock(ProductInventoryMapper.class);
    private final ProductServiceImpl service = new ProductServiceImpl(products, inventories);

    @BeforeAll
    static void mapping() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "product-update-test");
        assistant.setCurrentNamespace(ProductMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, ProductDo.class);
    }

    @Test
    void invalidCallArgumentsAreRejectedBeforeDatabaseAccess() {
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertEquals(ErrorCode.REQUEST_INVALID,
                    assertThrows(BusinessException.class, () -> service.update(id, request())).getErrorCode());
        }
        assertEquals(ErrorCode.REQUEST_INVALID,
                assertThrows(BusinessException.class, () -> service.update(1L, null)).getErrorCode());
        verifyNoInteractions(products, inventories);
    }

    @Test
    @SuppressWarnings("unchecked")
    void zeroChangedRowsStillReturnsAnExistingUnchangedProduct() {
        ProductDo product = new ProductDo();
        product.setId(1L);
        product.setName("编辑商品");
        product.setPrice(new BigDecimal("19.90"));
        ProductInventoryDo inventory = new ProductInventoryDo();
        inventory.setProductId(1L);
        inventory.setStock(5);
        when(products.update(isNull(), any(Wrapper.class))).thenReturn(0);
        when(products.selectById(1L)).thenReturn(product);
        when(inventories.selectById(1L)).thenReturn(inventory);

        var result = service.update(1L, request());

        assertEquals(1L, result.getId());
        assertEquals("编辑商品", result.getName());
        assertEquals(new BigDecimal("19.90"), result.getPrice());
        assertEquals(5, result.getStock());
    }

    @Test
    void nonexistentProductReturnsNotFoundWithoutReadingInventory() {
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND,
                assertThrows(BusinessException.class, () -> service.update(1L, request())).getErrorCode());
        verifyNoInteractions(inventories);
    }

    private UpdateProductRequest request() {
        UpdateProductRequest request = new UpdateProductRequest();
        request.setName("编辑商品");
        request.setPrice(new BigDecimal("19.90"));
        return request;
    }
}
