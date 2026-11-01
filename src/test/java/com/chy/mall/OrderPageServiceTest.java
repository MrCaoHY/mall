package com.chy.mall;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chy.mall.dto.OrderPageQuery;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.*;
import com.chy.mall.service.impl.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 查询纯逻辑边界：非法参数不查库，空页或查无订单不继续查明细。 */
class OrderPageServiceTest {
    private final OrderMapper mapper = mock(OrderMapper.class);
    private final OrderItemMapper items = mock(OrderItemMapper.class);
    private final OrderServiceImpl service = new OrderServiceImpl(mapper, items, mock(ProductInventoryMapper.class),
            mock(OrderCreateTransactionService.class), mock(OrderOperationLogService.class));

    @BeforeAll
    static void mapping() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "order-page-test");
        assistant.setCurrentNamespace(OrderMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, OrderDo.class);
    }

    @Test
    void invalidParametersAreRejectedBeforeDatabaseAccess() {
        assertThrows(BusinessException.class, () -> service.page(null, new OrderPageQuery()));
        assertThrows(BusinessException.class, () -> service.page(0L, new OrderPageQuery()));
        assertThrows(BusinessException.class, () -> service.page(1L, null));
        OrderPageQuery query = new OrderPageQuery();
        query.setPage(null);
        assertThrows(BusinessException.class, () -> service.page(1L, query));
        query.setPage(0);
        assertThrows(BusinessException.class, () -> service.page(1L, query));
        query.setPage(1);
        for (Integer size : new Integer[]{null, 0, 101}) {
            query.setSize(size);
            assertEquals(ErrorCode.REQUEST_INVALID,
                    assertThrows(BusinessException.class, () -> service.page(1L, query)).getErrorCode());
        }
        verifyNoInteractions(mapper, items);
    }

    @Test
    void invalidBuyerIsRejectedConsistentlyByEveryOrderEntry() {
        for (Long invalid : new Long[]{null, 0L, -1L}) {
            assertEquals(ErrorCode.BUYER_IDENTITY_INVALID,
                    assertThrows(BusinessException.class, () -> service.create(invalid, null)).getErrorCode());
            assertEquals(ErrorCode.BUYER_IDENTITY_INVALID,
                    assertThrows(BusinessException.class, () -> service.cancel(invalid, 1L)).getErrorCode());
            assertEquals(ErrorCode.BUYER_IDENTITY_INVALID,
                    assertThrows(BusinessException.class, () -> service.pay(invalid, 1L)).getErrorCode());
            assertEquals(ErrorCode.BUYER_IDENTITY_INVALID,
                    assertThrows(BusinessException.class, () -> service.listOperations(invalid, 1L)).getErrorCode());
            assertEquals(ErrorCode.BUYER_IDENTITY_INVALID,
                    assertThrows(BusinessException.class, () -> service.page(invalid, new OrderPageQuery())).getErrorCode());
            assertEquals(ErrorCode.BUYER_IDENTITY_INVALID,
                    assertThrows(BusinessException.class, () -> service.findById(invalid, 1L)).getErrorCode());
        }
        verifyNoInteractions(mapper, items);
    }

    @Test
    void invalidDetailOrderIdIsRejectedBeforeDatabaseAccess() {
        for (Long invalid : new Long[]{null, 0L, -1L}) {
            assertEquals(ErrorCode.REQUEST_INVALID,
                    assertThrows(BusinessException.class, () -> service.findById(1L, invalid)).getErrorCode());
        }
        verifyNoInteractions(mapper, items);
    }

    @Test
    void missingDetailStopsBeforeReadingItems() {
        assertEquals(ErrorCode.ORDER_NOT_FOUND,
                assertThrows(BusinessException.class, () -> service.findById(1L, 42L)).getErrorCode());
        verifyNoInteractions(items);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageBeyondEndKeepsTotalAndDoesNotQueryDetails() {
        when(mapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenAnswer(invocation -> new Page<OrderDo>(50, 10).setTotal(3));
        OrderPageQuery query = new OrderPageQuery();
        query.setPage(50);
        var result = service.page(1L, query);
        assertEquals(3L, result.getTotal());
        assertEquals(50, result.getPage());
        assertEquals(10, result.getSize());
        assertNotNull(result.getRecords());
        assertTrue(result.getRecords().isEmpty());
        verifyNoInteractions(items);
    }
}
