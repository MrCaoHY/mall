package com.chy.mall;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.chy.mall.dto.UpdateProductStatusRequest;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.service.impl.ProductServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 关键调用边界与0行同值分支；状态落库和下单影响交由真实库测试验证。 */
class ProductStatusServiceTest {
    private final ProductMapper products = mock(ProductMapper.class);
    private final ProductInventoryMapper inventories = mock(ProductInventoryMapper.class);
    private final ProductServiceImpl service = new ProductServiceImpl(products, inventories);

    @BeforeAll
    static void mapping() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "product-status-test");
        assistant.setCurrentNamespace(ProductMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, ProductDo.class);
    }

    @Test
    void invalidCallArgumentsAreRejectedBeforeDatabaseAccess() {
        for (Long id : new Long[]{null, 0L, -1L}) {
            assertEquals(ErrorCode.REQUEST_INVALID,
                    assertThrows(BusinessException.class, () -> service.updateStatus(id, request())).getErrorCode());
        }
        assertEquals(ErrorCode.REQUEST_INVALID,
                assertThrows(BusinessException.class, () -> service.updateStatus(1L, null)).getErrorCode());
        verifyNoInteractions(products, inventories);
    }

    @Test
    void zeroChangedRowsStillReturnsExistingTargetStatus() {
        ProductDo product = new ProductDo();
        product.setId(1L);
        product.setStatus(ProductStatus.OFF_SALE);
        ProductInventoryDo inventory = new ProductInventoryDo();
        inventory.setProductId(1L);
        inventory.setStock(5);
        when(products.selectById(1L)).thenReturn(product);
        when(inventories.selectById(1L)).thenReturn(inventory);
        // Mock的int默认返回0，模拟连接按实际变化行数报告同值更新。
        var result = service.updateStatus(1L, request());
        assertEquals(ProductStatus.OFF_SALE, result.getStatus());
        assertEquals(5, result.getStock());
    }

    private UpdateProductStatusRequest request() {
        UpdateProductStatusRequest request = new UpdateProductStatusRequest();
        request.setStatus(ProductStatus.OFF_SALE);
        return request;
    }
}
