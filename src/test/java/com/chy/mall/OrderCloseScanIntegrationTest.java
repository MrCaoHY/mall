package com.chy.mall;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.enums.OrderStatus;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.service.impl.OrderCloseScanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL验证全局候选查询；仅使用本次专用订单，成功清理、失败保留。 */
@SpringBootTest
@EnabledIfSystemProperty(named = "mall.order.scan.enabled", matches = "true")
class OrderCloseScanIntegrationTest {
    @Autowired private OrderCloseScanService scanService;
    @Autowired private OrderMapper orderMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void testJwtSecret(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String secret = Base64.getEncoder().encodeToString(key);
        registry.add("jwt.secret", () -> secret);
    }

    @Test
    void returnsFirstHundredEligibleIdsInStableOrderWithoutChangingOrders() throws Exception {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "测试本身不能开启事务");
        assertNotNull(jdbc.getDataSource());
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
        }
        // 使用远早于业务订单的截止时间；如果已有候选，不触碰旧数据，也不制造不可靠的排序断言。
        LocalDateTime cutoff = LocalDateTime.of(1970, 1, 2, 0, 0);
        assertTrue(scanService.findExpiredOrderIds(cutoff).isEmpty(), "历史截止时间已有候选订单，请使用隔离测试库");
        List<Long> fixtureIds = new ArrayList<>();
        List<Long> sameTimeIds = new ArrayList<>();
        List<Long> earliestId = new ArrayList<>();
        boolean passed = false;
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                // 同一截止时间创建101单；再创建更早的一单，验证排序而非插入顺序。
                for (int i = 0; i < 101; i++) {
                    sameTimeIds.add(insertOrder(fixtureIds, OrderStatus.PENDING_PAYMENT, cutoff));
                }
                earliestId.add(insertOrder(fixtureIds, OrderStatus.PENDING_PAYMENT, cutoff.minusSeconds(1)));
                insertOrder(fixtureIds, OrderStatus.PENDING_PAYMENT, cutoff.plusSeconds(1));
                insertOrder(fixtureIds, OrderStatus.PAID, cutoff.minusSeconds(1));
                insertOrder(fixtureIds, OrderStatus.CANCELLED, cutoff.minusSeconds(1));
            });
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "准备数据必须独立提交");
            List<Long> expected = new ArrayList<>();
            expected.add(earliestId.getFirst());
            expected.addAll(sameTimeIds.subList(0, 99));
            List<Long> actual = scanService.findExpiredOrderIds(cutoff);
            assertEquals(100, actual.size());
            assertEquals(expected, actual, "按到期时间及ID升序选出准确首批，含截止边界并排除其他状态");
            assertFalse(actual.contains(sameTimeIds.get(99)));
            assertEquals((long) fixtureIds.size(), orderMapper.selectCount(Wrappers.<OrderDo>lambdaQuery()
                    .in(OrderDo::getId, fixtureIds)));
            assertEquals(OrderStatus.PENDING_PAYMENT, orderMapper.selectById(earliestId.getFirst()).getStatus());
            passed = true;
        } finally {
            if (passed) {
                new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                        assertEquals(fixtureIds.size(), orderMapper.delete(Wrappers.<OrderDo>lambdaQuery()
                                .in(OrderDo::getId, fixtureIds))));
                assertEquals(0L, orderMapper.selectCount(Wrappers.<OrderDo>lambdaQuery()
                        .in(OrderDo::getId, fixtureIds)), "只清理本次专用订单");
            } else {
                System.out.println("SCAN_TEST_FIXTURE_IDS=" + fixtureIds);
            }
        }
        assertTrue(scanService.findExpiredOrderIds(cutoff).isEmpty());
    }

    /** 只构造扫描所需订单主表，订单号和请求号随机，避免修改商品、库存和既有订单。 */
    private Long insertOrder(List<Long> fixtureIds, OrderStatus status, LocalDateTime expiresAt) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        OrderDo order = new OrderDo();
        order.setOrderNo("SC" + unique.substring(0, 30));
        order.setBuyerId(1L);
        order.setRequestId(unique);
        order.setRequestFingerprint("0".repeat(64));
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal("1.00"));
        order.setExpiresAt(expiresAt);
        order.setCreatedAt(expiresAt.minusMinutes(30));
        order.setUpdatedAt(expiresAt.minusMinutes(30));
        if (status == OrderStatus.PAID) {
            order.setPaidAt(expiresAt.minusMinutes(1));
        } else if (status == OrderStatus.CANCELLED) {
            order.setCancelledAt(expiresAt.minusMinutes(1));
        }
        assertEquals(1, orderMapper.insert(order));
        assertNotNull(order.getId());
        fixtureIds.add(order.getId());
        return order.getId();
    }
}
