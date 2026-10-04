package com.chy.mall;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.chy.mall.dto.CreateOrderItemRequest;
import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.entity.OrderItemDo;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.OrderStatus;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.mapper.OrderItemMapper;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.service.impl.OrderServiceImpl;
import com.chy.mall.vo.OrderItemVo;
import com.chy.mall.vo.OrderVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class OrderServiceTest {
    private final ProductInventoryMapper inventoryMapper = mock(ProductInventoryMapper.class);
    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);

    private final OrderServiceImpl service = new OrderServiceImpl(inventoryMapper, productMapper, orderMapper, orderItemMapper);

    @BeforeAll
    static void initializeInventoryMapping() {
        // LambdaUpdateWrapper需要实体字段与数据库列的映射元数据。
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "unit-test-product-inventory");
        assistant.setCurrentNamespace(ProductInventoryMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, ProductInventoryDo.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createBuildsOrderFromDatabaseProductsAndSavesAllData() {
        ProductDo product20 = product(20L, "SKU-20", "机械键盘", "12.50");
        ProductDo product10 = product(10L, "SKU-10", "无线鼠标", "3.20");
        CreateOrderRequest request = request(
                item(20L, 2),
                item(10L, 3));

        // 故意按20、10返回，验证Service不依赖IN查询的返回顺序。
        when(productMapper.selectByIds(anyCollection())).thenReturn(List.of(product20, product10));

        when(inventoryMapper.update(isNull(), any(Wrapper.class))).thenReturn(1, 1);

        when(orderMapper.insert(any(OrderDo.class))).thenAnswer(invocation -> {
                    OrderDo order = invocation.getArgument(0);
                    order.setId(9001L);
                    return 1;
                });

        when(orderItemMapper.batchInsert(anyList())).thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());

        OrderVo result = service.create(77L, request);

        assertEquals(9001L, result.getId());
        assertNotNull(result.getOrderNo());
        assertTrue(result.getOrderNo().matches("[0-9a-f]{32}"));
        assertEquals(OrderStatus.PENDING_PAYMENT, result.getStatus());
        assertAmount("34.60", result.getTotalAmount());
        assertNotNull(result.getCreatedAt());
        assertEquals(result.getCreatedAt().plusMinutes(30), result.getExpiresAt());
        assertEquals(2, result.getItems().size());
        assertItem(result.getItems().get(0), 10L, "SKU-10", "无线鼠标", "3.20", 3, "9.60");
        assertItem(result.getItems().get(1), 20L, "SKU-20", "机械键盘", "12.50", 2, "25.00");

        ArgumentCaptor<OrderDo> orderCaptor = ArgumentCaptor.forClass(OrderDo.class);
        verify(orderMapper).insert(orderCaptor.capture());
        OrderDo insertedOrder = orderCaptor.getValue();
        assertEquals(77L, insertedOrder.getBuyerId());
        assertEquals(OrderStatus.PENDING_PAYMENT, insertedOrder.getStatus());
        assertAmount("34.60", insertedOrder.getTotalAmount());
        assertEquals(insertedOrder.getCreatedAt().plusMinutes(30), insertedOrder.getExpiresAt());

        ArgumentCaptor<List<OrderItemDo>> itemsCaptor = ArgumentCaptor.forClass(List.class);
        verify(orderItemMapper).batchInsert(itemsCaptor.capture());
        List<OrderItemDo> insertedItems = itemsCaptor.getValue();
        assertEquals(2, insertedItems.size());
        assertItem(insertedItems.get(0), 9001L, 10L, "SKU-10", "无线鼠标", "3.20", 3, "9.60");
        assertItem(insertedItems.get(1), 9001L, 20L, "SKU-20", "机械键盘", "12.50", 2, "25.00");

        verify(productMapper).selectByIds(anyCollection());
        verify(inventoryMapper, times(2)).update(isNull(), any(Wrapper.class));
        verifyNoMoreInteractions(productMapper, inventoryMapper, orderMapper, orderItemMapper);
    }

    private static ProductDo product(Long id, String sku, String name, String price) {
        ProductDo product = new ProductDo();
        product.setId(id);
        product.setSku(sku);
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        product.setStatus(ProductStatus.ON_SALE);
        return product;
    }

    private static CreateOrderItemRequest item(Long productId, Integer quantity) {
        CreateOrderItemRequest item = new CreateOrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
    }

    private static CreateOrderRequest request(CreateOrderItemRequest... items) {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setItems(List.of(items));
        return request;
    }

    private static void assertItem(
            OrderItemVo item, Long productId, String sku, String name,
            String unitPrice, Integer quantity, String lineAmount) {
        assertEquals(productId, item.getProductId());
        assertEquals(sku, item.getSku());
        assertEquals(name, item.getName());
        assertAmount(unitPrice, item.getUnitPrice());
        assertEquals(quantity, item.getQuantity());
        assertAmount(lineAmount, item.getLineAmount());
    }

    private static void assertItem(OrderItemDo item, Long orderId, Long productId, String sku, String name,
            String unitPrice, Integer quantity, String lineAmount) {
        assertEquals(orderId, item.getOrderId());
        assertEquals(productId, item.getProductId());
        assertEquals(sku, item.getSkuSnapshot());
        assertEquals(name, item.getNameSnapshot());
        assertAmount(unitPrice, item.getUnitPrice());
        assertEquals(quantity, item.getQuantity());
        assertAmount(lineAmount, item.getLineAmount());
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
