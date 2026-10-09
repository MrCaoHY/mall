package com.chy.mall;

import com.chy.mall.dto.CreateProductRequest;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.service.impl.ProductServiceImpl;
import com.chy.mall.vo.ProductVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
public class ProductCreateServiceTest {
    @Mock
    private ProductMapper productMapper;

    @Mock
    private ProductInventoryMapper productInventoryMapper;

    @InjectMocks
    private ProductServiceImpl service;

    @Test
    public void createPropagatesProductInsertFailure() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("模拟插入失败");
        when(productMapper.insert(any(ProductDo.class))).thenThrow(failure);
        CreateProductRequest productRequest = new CreateProductRequest();
        productRequest.setSku("MOCK-001");
        productRequest.setName("机械键盘");
        productRequest.setPrice(BigDecimal.valueOf(99.00));
        productRequest.setInitialStock(10);
        DataAccessResourceFailureException actual = assertThrows(DataAccessResourceFailureException.class, () -> service.create(productRequest));
        assertSame(failure, actual);
        ArgumentCaptor<ProductDo> captor = ArgumentCaptor.forClass(ProductDo.class);
        verify(productMapper).insert(captor.capture());
        ProductDo captured = captor.getValue();
        assertEquals("MOCK-001", captured.getSku());
        assertEquals("机械键盘", captured.getName());
        assertEquals(BigDecimal.valueOf(99.00), captured.getPrice());
        assertEquals(ProductStatus.ON_SALE, captured.getStatus());
        verifyNoInteractions(productInventoryMapper);
    }

    @Test
    void createReturnsProductWithGeneratedIdAndSavesInventory() {
        CreateProductRequest productRequest = new CreateProductRequest();
        productRequest.setSku("MOCK-001");
        productRequest.setName("机械键盘");
        productRequest.setPrice(BigDecimal.valueOf(99.00));
        productRequest.setInitialStock(10);
        when(productMapper.insert(any(ProductDo.class))).thenAnswer(invocation -> {
                    ProductDo product = invocation.getArgument(0);
                    product.setId(9001L);
                    return 1;
                }
        );
        when(productInventoryMapper.insert(any(ProductInventoryDo.class))).thenReturn(1);
        ProductVo result = service.create(productRequest);
        ArgumentCaptor<ProductDo> productCaptor = ArgumentCaptor.forClass(ProductDo.class);
        verify(productMapper).insert(productCaptor.capture());
        ProductDo product = productCaptor.getValue();
        assertEquals("MOCK-001", product.getSku());
        assertEquals("机械键盘", product.getName());
        assertEquals(BigDecimal.valueOf(99.00), product.getPrice());
        assertEquals(ProductStatus.ON_SALE, product.getStatus());
        ArgumentCaptor<ProductInventoryDo> productInventoryCaptor = ArgumentCaptor.forClass(ProductInventoryDo.class);
        verify(productInventoryMapper).insert(productInventoryCaptor.capture());
        ProductInventoryDo inventoryDo = productInventoryCaptor.getValue();
        assertEquals(9001L, inventoryDo.getProductId());
        assertEquals(10, inventoryDo.getStock());
        assertEquals(9001L, result.getId());
        assertEquals(ProductStatus.ON_SALE, result.getStatus());
        assertEquals("MOCK-001", result.getSku());
        assertEquals(10, result.getStock());
        assertEquals("机械键盘", result.getName());
        assertEquals(BigDecimal.valueOf(99.00), result.getPrice());

    }

    @Test
    void createPropagatesInventoryInsertFailure() {
        CreateProductRequest productRequest = new CreateProductRequest();
        productRequest.setSku("MOCK-001");
        productRequest.setName("机械键盘");
        productRequest.setPrice(BigDecimal.valueOf(99.00));
        productRequest.setInitialStock(10);
        when(productMapper.insert(any(ProductDo.class))).thenAnswer(invocationOnMock -> {
            ProductDo product = invocationOnMock.getArgument(0);
            product.setId(9001L);
            return 1;
        });
        DataAccessResourceFailureException dataAccessResourceFailureException = new DataAccessResourceFailureException("模拟库存插入失败");
        when(productInventoryMapper.insert(any(ProductInventoryDo.class))).thenThrow(dataAccessResourceFailureException);
        DataAccessResourceFailureException exception = assertThrows(DataAccessResourceFailureException.class, () -> service.create(productRequest));
        assertSame(dataAccessResourceFailureException, exception);
        ArgumentCaptor<ProductDo> productCaptor = ArgumentCaptor.forClass(ProductDo.class);
        verify(productMapper).insert(productCaptor.capture());
        ProductDo product = productCaptor.getValue();
        assertEquals("MOCK-001", product.getSku());
        assertEquals("机械键盘", product.getName());
        assertEquals(BigDecimal.valueOf(99.00), product.getPrice());
        assertEquals(ProductStatus.ON_SALE, product.getStatus());
        ArgumentCaptor<ProductInventoryDo> productInventoryCaptor = ArgumentCaptor.forClass(ProductInventoryDo.class);
        verify(productInventoryMapper).insert(productInventoryCaptor.capture());
        ProductInventoryDo inventoryDo = productInventoryCaptor.getValue();
        assertEquals(9001L, inventoryDo.getProductId());
        assertEquals(10, inventoryDo.getStock());
    }

    @Test
    void createRejectsZeroInventoryInsertRows() {
        CreateProductRequest productRequest = new CreateProductRequest();
        productRequest.setSku("MOCK-002");
        productRequest.setName("无线鼠标");
        productRequest.setPrice(BigDecimal.valueOf(19.90));
        productRequest.setInitialStock(3);
        when(productMapper.insert(any(ProductDo.class))).thenAnswer(invocationOnMock -> {
            ProductDo product = invocationOnMock.getArgument(0);
            product.setId(7001L);
            return 1;
        });
        ArgumentCaptor<ProductDo> productCaptor = ArgumentCaptor.forClass(ProductDo.class);
        ArgumentCaptor<ProductInventoryDo> inventoryCaptor = ArgumentCaptor.forClass(ProductInventoryDo.class);
        when(productInventoryMapper.insert(any(ProductInventoryDo.class))).thenReturn(0);
        IllegalStateException illegalStateException = assertThrows(IllegalStateException.class, () -> service.create(productRequest));
        assertEquals("库存保存失败", illegalStateException.getMessage());
        verify(productMapper, times(1)).insert(productCaptor.capture());
        ProductDo product = productCaptor.getValue();
        assertEquals(ProductStatus.ON_SALE, product.getStatus());
        assertEquals("MOCK-002", product.getSku());
        assertEquals("无线鼠标", product.getName());
        assertEquals(productRequest.getPrice(), product.getPrice());
        verify(productInventoryMapper).insert(inventoryCaptor.capture());
        ProductInventoryDo inventoryDo = inventoryCaptor.getValue();
        assertEquals(7001L, inventoryDo.getProductId());
        assertEquals(3, inventoryDo.getStock());

    }
    @Test
    void createSupportsZeroInitialStock() {
        //初始库存为0的商品
        CreateProductRequest productRequest = new CreateProductRequest();
        productRequest.setSku("FINAL-ZERO-STOCK");
        productRequest.setName("零库存商品");
        productRequest.setPrice(BigDecimal.valueOf(0.01));
        productRequest.setInitialStock(0);
        when(productMapper.insert(any(ProductDo.class))).thenAnswer(invocation->{
            ProductDo productDo = invocation.getArgument(0);
            productDo.setId(8101L);
            return 1;
        });
        when(productInventoryMapper.insert(any(ProductInventoryDo.class))).thenReturn(1);
        ProductVo productVo = service.create(productRequest);
        assertEquals(ProductStatus.ON_SALE, productVo.getStatus());
        assertEquals("FINAL-ZERO-STOCK", productVo.getSku());
        assertEquals(0, productVo.getStock());
        assertEquals(8101L,productVo.getId());
        assertEquals(BigDecimal.valueOf(0.01), productVo.getPrice());
        assertEquals("零库存商品", productVo.getName());
//        verify(productMapper,times(1)).insert(any(ProductDo.class));
//        verify(productInventoryMapper,times(1)).insert(any(ProductInventoryDo.class));

        ArgumentCaptor<ProductDo>  productCaptor = ArgumentCaptor.forClass(ProductDo.class);
        ArgumentCaptor<ProductInventoryDo> productInventoryCaptor = ArgumentCaptor.forClass(ProductInventoryDo.class);
        verify(productMapper,times(1)).insert(productCaptor.capture());
        ProductDo product = productCaptor.getValue();
        assertEquals("FINAL-ZERO-STOCK", product.getSku());
        assertEquals("零库存商品", product.getName());
        assertEquals(BigDecimal.valueOf(0.01), product.getPrice());
        assertEquals(ProductStatus.ON_SALE, product.getStatus());
        assertEquals(8101L,product.getId());
        verify(productInventoryMapper,times(1)).insert(productInventoryCaptor.capture());
        ProductInventoryDo inventory = productInventoryCaptor.getValue();
        assertEquals(8101L, inventory.getProductId());
        assertEquals(0, inventory.getStock());
    }

}
