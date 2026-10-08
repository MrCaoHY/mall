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
    void createReturnsProductWithGeneratedIdAndSavesInventory(){
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
        assertEquals(9001L,inventoryDo.getProductId());
        assertEquals(10,inventoryDo.getStock());
        assertEquals(9001L,result.getId());
        assertEquals(ProductStatus.ON_SALE, result.getStatus());
        assertEquals("MOCK-001", result.getSku());
        assertEquals(10, result.getStock());
        assertEquals("机械键盘", result.getName());
        assertEquals(BigDecimal.valueOf(99.00), result.getPrice());

    }

    @Test
    void createPropagatesInventoryInsertFailure(){
        CreateProductRequest productRequest = new CreateProductRequest();
        productRequest.setSku("MOCK-001");
        productRequest.setName("机械键盘");
        productRequest.setPrice(BigDecimal.valueOf(99.00));
        productRequest.setInitialStock(10);
        when(productMapper.insert(any(ProductDo.class))).thenAnswer(invocationOnMock ->  {
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
        assertEquals(9001L,inventoryDo.getProductId());
        assertEquals(10,inventoryDo.getStock());
    }
}
