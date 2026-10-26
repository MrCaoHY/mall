package com.chy.mall;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.entity.OrderItemDo;
import com.chy.mall.enums.OrderStatus;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.OrderItemMapper;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.service.impl.OrderCreateTransactionService;
import com.chy.mall.service.impl.OrderServiceImpl;
import com.chy.mall.service.impl.OrderOperationLogService;
import com.chy.mall.enums.OrderOperationAction;
import com.chy.mall.vo.OrderVo;
import com.chy.mall.vo.OrderItemVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OrderPayServiceTest {
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private ProductInventoryMapper productInventoryMapper;
    @Mock
    private OrderCreateTransactionService orderCreateTransactionService;
    @Mock
    private OrderOperationLogService operationLogService;
    @InjectMocks
    private OrderServiceImpl orderService;

    // 泛型Captor保留Wrapper类型，便于检查实际查询/更新参数。
    @Captor
    private ArgumentCaptor<LambdaUpdateWrapper<OrderDo>> updateCaptor;
    @Captor
    private ArgumentCaptor<LambdaQueryWrapper<OrderDo>> orderQueryCaptor;
    @Captor
    private ArgumentCaptor<LambdaQueryWrapper<OrderItemDo>> itemQueryCaptor;


    @BeforeAll
    static void init() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        // 1. 初始化 OrderDo 元数据（供 markOrderPaid 的 lambdaUpdate 和查单 lambdaQuery 使用）
        MapperBuilderAssistant orderAssistant = new MapperBuilderAssistant(configuration, "unit-test-orders");
        orderAssistant.setCurrentNamespace(OrderMapper.class.getName());
        TableInfoHelper.initTableInfo(orderAssistant, OrderDo.class);
        MapperBuilderAssistant orderItemAssistant = new MapperBuilderAssistant(configuration, "unit-test-Order-item");
        orderItemAssistant.setCurrentNamespace(OrderItemMapper.class.getName());
        TableInfoHelper.initTableInfo(orderItemAssistant, OrderItemDo.class);


    }
    @Test
    @SuppressWarnings("unchecked")
    void payReturnsPaidOrderWhenUpdateSucceeds() {
        OrderDo orderDo = new OrderDo();
        orderDo.setId(9001L);
        orderDo.setBuyerId(77L);
        orderDo.setOrderNo("PAY-UNIT-9001");
        orderDo.setStatus(OrderStatus.PAID);
        orderDo.setTotalAmount(new BigDecimal("19.90"));
        LocalDateTime localDateTime = LocalDateTime.now().withNano(0);
        orderDo.setCreatedAt(localDateTime);
        orderDo.setExpiresAt(localDateTime.plusMinutes(30));
        OrderItemDo orderItemDo = new OrderItemDo();
        orderItemDo.setOrderId(9001L);
        orderItemDo.setProductId(72L);
        orderItemDo.setSkuSnapshot("SKU-PAY-72");
        orderItemDo.setNameSnapshot("支付商品测试");
        orderItemDo.setUnitPrice(new BigDecimal("19.90"));
        orderItemDo.setQuantity(1);
        orderItemDo.setLineAmount(new BigDecimal("19.90"));
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(orderDo);
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(orderItemDo));
        OrderVo payResult = orderService.pay(77L, 9001L);
        assertEquals(9001L,payResult.getId());
        assertEquals("PAY-UNIT-9001", payResult.getOrderNo());
        assertEquals(OrderStatus.PAID,payResult.getStatus());
        assertEquals(new BigDecimal("19.90"), payResult.getTotalAmount());
        assertEquals(localDateTime, payResult.getCreatedAt());
        assertEquals(localDateTime.plusMinutes(30), payResult.getExpiresAt());
        // 核对返回明细，避免只验证主表而漏掉商品快照的组装。
        assertNotNull(payResult.getItems());
        assertEquals(1, payResult.getItems().size());
        OrderItemVo paidItem = payResult.getItems().get(0);
        assertEquals(72L, paidItem.getProductId());
        assertEquals("SKU-PAY-72", paidItem.getSku());
        assertEquals("支付商品测试", paidItem.getName());
        assertEquals(new BigDecimal("19.90"), paidItem.getUnitPrice());
        assertEquals(1, paidItem.getQuantity());
        assertEquals(new BigDecimal("19.90"), paidItem.getLineAmount());

        // 同时捕获真实参数并检查顺序：先竞争状态更新，再查询订单和明细。
        InOrder calls = inOrder(orderMapper, orderItemMapper);
        calls.verify(orderMapper).update(isNull(), updateCaptor.capture());
        calls.verify(orderMapper).selectOne(orderQueryCaptor.capture());
        calls.verify(orderItemMapper).selectList(itemQueryCaptor.capture());
        verifyNoMoreInteractions(orderMapper, orderItemMapper);
        // 下单已扣库存，支付不能再次操作库存或调用创建订单事务。
        verifyNoInteractions(productInventoryMapper, orderCreateTransactionService);

        LambdaUpdateWrapper<OrderDo> update = updateCaptor.getValue();
        String setSql = update.getSqlSet();
        // WHERE参数延迟生成，先读取SQL片段再获取完整参数表。
        String whereSql = update.getSqlSegment();
        Map<String, Object> updateParameters = update.getParamNameValuePairs();

        // 查询stub预设了PAID，所以还要检查UPDATE确实写PAID而非CANCELLED。
        assertEquals(OrderStatus.PAID, boundValue(setSql, "status", "=", updateParameters));
        LocalDateTime payTime = assertInstanceOf(LocalDateTime.class,
                boundValue(setSql, "paid_at", "=", updateParameters));
        assertEquals(0, payTime.getNano());
        verify(operationLogService).recordOperation(9001L, 77L, OrderOperationAction.PAY, payTime);
        verifyNoMoreInteractions(operationLogService);
        assertEquals(payTime, boundValue(setSql, "updated_at", "=", updateParameters));
        assertFalse(setSql.contains("cancelled_at"), "支付不能写入取消时间");

        assertEquals(9001L, boundValue(whereSql, "id", "=", updateParameters));
        assertEquals(77L, boundValue(whereSql, "buyer_id", "=", updateParameters));
        assertEquals(OrderStatus.PENDING_PAYMENT,
                boundValue(whereSql, "status", "=", updateParameters));
        assertEquals(payTime, boundValue(whereSql, "expires_at", ">", updateParameters));

        // Mock不执行查询，需单独核对查询条件，防止返回预设数据掩盖归属错误。
        LambdaQueryWrapper<OrderDo> orderQuery = orderQueryCaptor.getValue();
        String orderWhere = orderQuery.getSqlSegment();
        assertEquals(9001L, boundValue(orderWhere, "id", "=", orderQuery.getParamNameValuePairs()));
        assertEquals(77L, boundValue(orderWhere, "buyer_id", "=", orderQuery.getParamNameValuePairs()));

        LambdaQueryWrapper<OrderItemDo> itemQuery = itemQueryCaptor.getValue();
        String itemWhere = itemQuery.getSqlSegment();
        assertEquals(9001L, boundValue(itemWhere, "order_id", "=", itemQuery.getParamNameValuePairs()));
    }


    /**
     * 已支付订单的原期限即使已经过去，重复支付仍返回原结果。
     * Mock的0行表示本次没有转换状态；真实SQL和数据库时间保持由集成验收负责。
     */
    @Test
    @SuppressWarnings("unchecked")
    void payReturnsExistingOrderWhenAlreadyPaid() {
        LocalDateTime createdAt = LocalDateTime.now().withNano(0).minusHours(1);
        LocalDateTime firstPaidAt = createdAt.plusMinutes(5);
        OrderDo paidOrder = new OrderDo();
        paidOrder.setId(9002L);
        paidOrder.setBuyerId(77L);
        paidOrder.setOrderNo("PAY-REPLAY-9002");
        paidOrder.setStatus(OrderStatus.PAID);
        paidOrder.setTotalAmount(new BigDecimal("19.90"));
        paidOrder.setCreatedAt(createdAt);
        paidOrder.setExpiresAt(createdAt.plusMinutes(30));
        paidOrder.setPaidAt(firstPaidAt);
        paidOrder.setUpdatedAt(firstPaidAt);

        OrderItemDo savedItem = new OrderItemDo();
        savedItem.setOrderId(9002L);
        savedItem.setProductId(72L);
        savedItem.setSkuSnapshot("SKU-PAY-72");
        savedItem.setNameSnapshot("支付商品测试");
        savedItem.setUnitPrice(new BigDecimal("19.90"));
        savedItem.setQuantity(1);
        savedItem.setLineAmount(new BigDecimal("19.90"));

        // 直接模拟这一次重试：仍尝试条件UPDATE，但没有获得新的状态转换资格。
        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(paidOrder);
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(savedItem));

        OrderVo replay = assertDoesNotThrow(() -> orderService.pay(77L, 9002L));
        assertNotNull(replay);
        assertEquals(9002L, replay.getId());
        assertEquals("PAY-REPLAY-9002", replay.getOrderNo());
        assertEquals(OrderStatus.PAID, replay.getStatus());
        assertEquals(new BigDecimal("19.90"), replay.getTotalAmount());
        assertEquals(createdAt, replay.getCreatedAt());
        assertEquals(createdAt.plusMinutes(30), replay.getExpiresAt());
        assertNotNull(replay.getItems());
        assertEquals(1, replay.getItems().size());
        OrderItemVo replayItem = replay.getItems().get(0);
        assertEquals(72L, replayItem.getProductId());
        assertEquals("SKU-PAY-72", replayItem.getSku());
        assertEquals("支付商品测试", replayItem.getName());
        assertEquals(new BigDecimal("19.90"), replayItem.getUnitPrice());
        assertEquals(1, replayItem.getQuantity());
        assertEquals(new BigDecimal("19.90"), replayItem.getLineAmount());

        // 只检查返回实体没有被Service在内存中改写，不将此断言当作数据库证据。
        assertEquals(OrderStatus.PAID, paidOrder.getStatus());
        assertEquals(firstPaidAt, paidOrder.getPaidAt());
        assertEquals(firstPaidAt, paidOrder.getUpdatedAt());

        InOrder calls = inOrder(orderMapper, orderItemMapper);
        calls.verify(orderMapper).update(isNull(), updateCaptor.capture());
        calls.verify(orderMapper).selectOne(orderQueryCaptor.capture());
        calls.verify(orderItemMapper).selectList(itemQueryCaptor.capture());
        // 不能跳过第一次条件UPDATE，也不能在识别已支付后追加第二次更新。
        verifyNoMoreInteractions(orderMapper, orderItemMapper);
        verifyNoInteractions(productInventoryMapper, orderCreateTransactionService, operationLogService);

        LambdaUpdateWrapper<OrderDo> update = updateCaptor.getValue();
        String updateWhere = update.getSqlSegment();
        assertEquals(9002L, boundValue(updateWhere, "id", "=", update.getParamNameValuePairs()));
        assertEquals(77L, boundValue(updateWhere, "buyer_id", "=", update.getParamNameValuePairs()));
        assertEquals(OrderStatus.PENDING_PAYMENT,
                boundValue(updateWhere, "status", "=", update.getParamNameValuePairs()));

        // 依旧按当前买家查询原订单，并按原订单主键查询明细。
        LambdaQueryWrapper<OrderDo> orderQuery = orderQueryCaptor.getValue();
        String orderWhere = orderQuery.getSqlSegment();
        assertEquals(9002L, boundValue(orderWhere, "id", "=", orderQuery.getParamNameValuePairs()));
        assertEquals(77L, boundValue(orderWhere, "buyer_id", "=", orderQuery.getParamNameValuePairs()));
        LambdaQueryWrapper<OrderItemDo> itemQuery = itemQueryCaptor.getValue();
        String itemWhere = itemQuery.getSqlSegment();
        assertEquals(9002L, boundValue(itemWhere, "order_id", "=", itemQuery.getParamNameValuePairs()));
    }

    /**
     * 已取消订单的条件更新影响0行，查询确认状态后拒绝支付，不再读取明细。
     */
    @Test
    @SuppressWarnings("unchecked")
    void payRejectsCancelledOrder() {
        OrderDo cancelledOrder = new OrderDo();
        cancelledOrder.setId(9003L);
        cancelledOrder.setBuyerId(77L);
        cancelledOrder.setStatus(OrderStatus.CANCELLED);

        when(orderMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(cancelledOrder);
        // 该分支在组装VO前抛异常，不配置不会使用的明细查询。

        BusinessException exception = assertThrows(BusinessException.class, () -> orderService.pay(77L, 9003L));
        assertEquals(ErrorCode.ORDER_CONFLICT, exception.getErrorCode());

        InOrder calls = inOrder(orderMapper);
        calls.verify(orderMapper).update(isNull(), updateCaptor.capture());
        calls.verify(orderMapper).selectOne(orderQueryCaptor.capture());
        verifyNoMoreInteractions(orderMapper);
        verifyNoInteractions(orderItemMapper, productInventoryMapper, orderCreateTransactionService, operationLogService);

        // Mock不执行SQL，检查本次尝试及查询仍定位当前买家的同一订单。
        LambdaUpdateWrapper<OrderDo> update = updateCaptor.getValue();
        String updateWhere = update.getSqlSegment();
        assertEquals(9003L, boundValue(updateWhere, "id", "=", update.getParamNameValuePairs()));
        assertEquals(77L, boundValue(updateWhere, "buyer_id", "=", update.getParamNameValuePairs()));
        assertEquals(OrderStatus.PENDING_PAYMENT,
                boundValue(updateWhere, "status", "=", update.getParamNameValuePairs()));
        LambdaQueryWrapper<OrderDo> query = orderQueryCaptor.getValue();
        String queryWhere = query.getSqlSegment();
        assertEquals(9003L, boundValue(queryWhere, "id", "=", query.getParamNameValuePairs()));
        assertEquals(77L, boundValue(queryWhere, "buyer_id", "=", query.getParamNameValuePairs()));
    }
    /**
     * 将某个列/比较符关联到实际绑定值，不依赖完整SQL文本或MP参数的生成编号。
     * 列名前的边界避免把id错误匹配到buyer_id或order_id。
     */
    private static Object boundValue(String sql, String column, String operator,
                                     Map<String, Object> parameters) {
        Pattern pattern = Pattern.compile("(?<![\\w])" + Pattern.quote(column)
                + "\\s*" + Pattern.quote(operator)
                + "\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}");
        Matcher matcher = pattern.matcher(sql);
        assertTrue(matcher.find(), "缺少字段条件或赋值：" + column + " " + operator);
        String parameterName = matcher.group(1);
        assertTrue(parameters.containsKey(parameterName), "缺少字段绑定值：" + column);
        return parameters.get(parameterName);
    }
}
