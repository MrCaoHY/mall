package com.chy.mall;

import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.service.impl.ProductServiceImpl;
import com.chy.mall.vo.ProductVo;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 练习mock
 */
public class ProductDetailServiceTest {
    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final ProductInventoryMapper productInventoryMapper = mock(ProductInventoryMapper.class);
    private final ProductServiceImpl service = new ProductServiceImpl(productMapper, productInventoryMapper);
    @Test
    void getByIdReturnsWithStock(){

        ProductDo product = new ProductDo();
        product.setId(7L);
        product.setName("机械键盘");
        ProductInventoryDo inventory = new ProductInventoryDo();
        inventory.setProductId(7L);
        inventory.setStock(10);
        // TODO 1：商品Mapper查询7L时，返回product
        when(productMapper.selectById(7L)).thenReturn(product);
        // TODO 2：库存Mapper查询7L时，返回inventory
        when(productInventoryMapper.selectById(7L)).thenReturn(inventory);
        // 真正执行被测业务方法
        ProductVo result = service.getById(7L);

        // TODO 3：断言商品名称为“机械键盘”，库存为10
        assertEquals("机械键盘",result.getName());
        assertEquals(Integer.valueOf(10), result.getStock());
        // TODO 4：分别验证两个Mapper都查询了7L，恰好一次
        verify(productMapper, times(1)).selectById(7L);
        verify(productInventoryMapper, times(1)).selectById(7L);
    }

    @Test
    void getByIdThrowsWhenProductMissing(){

        when(productMapper.selectById(7L)).thenReturn(null);
        BusinessException businessException = assertThrows(BusinessException.class, () -> service.getById(7L));
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, businessException.getErrorCode());
        verify(productMapper, times(1)).selectById(7L);
        verifyNoInteractions(productInventoryMapper);

    }

    @Test
    void getByIdThrowsWhenInventoryMissing() {
        ProductDo product = new ProductDo();
        product.setId(7L);
        product.setName("机械键盘");
        when(productMapper.selectById(7L)).thenReturn(product);
        when(productInventoryMapper.selectById(7L)).thenReturn(null);
//        service.getById(8L);
        BusinessException businessException = assertThrows(BusinessException.class, () -> service.getById(7L));
        assertEquals(ErrorCode.PRODUCT_INVENTORY_MISSING, businessException.getErrorCode());
        verify(productMapper, times(1)).selectById(7L);
        verify(productInventoryMapper, times(1)).selectById(7L);
    }

    @Test
    void getByIdPropagatesProductQueryFailure(){
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("模拟商品查询失败");
        when(productMapper.selectById(7L)).thenThrow(failure);
        DataAccessResourceFailureException actual = assertThrows(DataAccessResourceFailureException.class, () -> service.getById(7L));
        assertSame(failure, actual);
        verify(productMapper, times(1)).selectById(7L);
        verifyNoInteractions(productInventoryMapper);
    }


}
