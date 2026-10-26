package com.chy.mall;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.dto.CreateOrderItemRequest;
import com.chy.mall.dto.CreateOrderRequest;
import com.chy.mall.entity.OrderDo;
import com.chy.mall.entity.OrderItemDo;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.OrderOperationAction;
import com.chy.mall.enums.OrderStatus;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.OrderItemMapper;
import com.chy.mall.mapper.OrderMapper;
import com.chy.mall.mapper.OrderOperationLogMapper;
import com.chy.mall.entity.OrderOperationLogDo;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.service.OrderService;
import com.chy.mall.service.impl.OrderCloseTransactionService;
import com.chy.mall.vo.OrderVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.stubbing.Answer;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 取消订单的真实 MySQL 集成测试。
 *
 * 核心设计原则与防坑指南：
 * 1. 【测试本身不加 @Transactional】：
 *    如果测试类上加了 @Transactional，Spring 会在每个测试方法结束时自动 ROLLBACK。
 *    这会产生致命副作用：你断言通过的数据，其实在真实数据库里已经被 Spring 滚掉了；
 *    并且测试自身的事务会掩盖业务方法内部事务提交/回滚的真实行为。
 *    因此，测试必须以非事务状态运行，直接物理核对 Service 提交/回滚后的 MySQL 数据。
 *
 * 2. 【数据隔离与防污染（Fixture 模式）】：
 *    每个测试用例运行时，通过独立事务创建带有随机 UUID SKU 的专用商品与订单；
 *    测试成功时通过 finally 严格清理；测试失败时特意保留现场数据，方便开发者直连数据库排查原因。
 *
 * 3. 【真实数据库 + Spy 观察（绝非假 Mock）】：
 *    测试中的所有增删改查都是真实落库的，Mapper 上的 Spy 仅作为“发令枪（挂屏障）”与“观察仪（计数器）”，
 *    所有受影响行数均由 MySQL 存储引擎真实计算返回。
 *
 * 运行方式：
 * - 终端命令：.\mvnw.cmd "-Dtest=OrderCancelIntegrationTest" "-Dmall.order.cancel.enabled=true" test
 * - IDEA 运行：Run Configuration 的 VM options 加上 -Dmall.order.cancel.enabled=true
 */
@SpringBootTest(properties = {
        // 配置 Hikari 连接池与网络超时，防止并发断点调试或行锁等待时连接被意外断开
        "spring.datasource.hikari.connection-timeout=10000",
        "spring.datasource.hikari.data-source-properties.connectTimeout=5000",
        "spring.datasource.hikari.data-source-properties.socketTimeout=15000"
})
// 系统属性开关门禁：只有显式指定 -Dmall.order.cancel.enabled=true 时才跑，防止日常 CI 缺少真实 DB 时构建失败
@EnabledIfSystemProperty(named = "mall.order.cancel.enabled", matches = "true")
// 类内按顺序单线程执行：避免多个测试方法并发操作同一连接池，降低连接数耗尽与锁竞争干扰
@Execution(ExecutionMode.SAME_THREAD)
class OrderCancelIntegrationTest {
    private static final Long BUYER_ID = 1L;
    private static final int INITIAL_STOCK = 5;
    private static final int QUANTITY = 2;

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderCloseTransactionService closeTransactionService;
    @Autowired
    private ProductMapper productMapper;

    // 【关键技术点：MockitoSpyBean】
    // 注入 Spy 而不是 Mock：Spy 包装了真实的 MyBatis Mapper，默认所有调用全部直通数据库！
    // 允许我们在保留 100% 真实 SQL 执行的同时，插入 CyclicBarrier 屏障或验证调用次数。
    @MockitoSpyBean
    private ProductInventoryMapper inventoryMapper;
    @MockitoSpyBean
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private OrderOperationLogMapper operationLogMapper;

    /**
     * 【动态测试环境变量注入】：
     * 解决痛点：本地运行 @SpringBootTest 时，宿主机如果没有配置 JWT_SECRET，或者配置了非法字符，
     * 会导致 JwtUtil 初始化炸掉（之前遇到的 Illegal base64 character 异常）。
     * 此处在容器启动前，动态生成一个符合标准的 32 字节安全随机 Base64 密钥，让测试完全自闭环、零环境依赖！
     */
    @DynamicPropertySource
    static void testOnlyJwtSecret(DynamicPropertyRegistry registry) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String secret = Base64.getEncoder().encodeToString(key);
        registry.add("jwt.secret", () -> secret);
    }

    /**
     * 测试用例 1：首次取消恢复库存；重复取消返回同一快照，库存及取消时间不再变化。
     *
     * 验证重点：
     * 1. 首次取消成功将订单改为 CANCELLED，并且库存从 (5-2=3) 准确恢复回 5。
     * 2. 第二次对同一个 orderId 调用 cancel（顺序重试 / 幂等调用）：
     *    - 必须返回与首次相同的 OrderVo；
     *    - 底层 inventoryMapper.update 的实际调用次数严格维持为 1（不能因为重试再次加库存！）；
     *    - 数据库里的 cancelled_at 和 updated_at 时间必须保持原样，不能被刷新。
     */
    @Test
    @SuppressWarnings("unchecked")
    void cancelRestoresStockAndRepeatedCancelDoesNotRestoreAgain() {
        // 前置断言：确保注入的 Service 带有 Spring 动态代理（@Transactional 生效），且测试线程当前无活跃事务
        assertServiceBoundary();

        // 准备测试夹具：向数据库真实写入 1 个商品（初始库存 5）
        Fixture fixture = createFixture(1);
        boolean passed = false;
        try {
            // 下单购买 2 件：此时数据库订单状态为 PENDING_PAYMENT，库存实扣为 3
            OrderVo created = createPendingOrder(fixture);

            // 定位真实的 Mapper Spy，并清空下单阶段留下的调用记录计数器
            ProductInventoryMapper inventorySpy = mapperObserver(inventoryMapper);
            clearInvocations(inventorySpy);

            // ================= 首次调用取消 =================
            OrderVo cancelled = orderService.cancel(BUYER_ID, created.getId());

            // 验证返回快照、物理库存值、主表状态及时间
            assertCancelledSnapshot(created, cancelled);
            assertStock(fixture.products().getFirst().getId(), INITIAL_STOCK); // 恢复为 5
            OrderDo firstSaved = savedOrder(created.getId());
            assertEquals(OrderStatus.CANCELLED, firstSaved.getStatus());
            assertNotNull(firstSaved.getCancelledAt());
            assertEquals(firstSaved.getCancelledAt(), firstSaved.getUpdatedAt());

            // ================= 第二次幂等重试取消 =================
            OrderVo repeated = orderService.cancel(BUYER_ID, created.getId());

            // 验证幂等契约：对象一致，库存没多加，主表记录没有被二度修改
            assertEquals(cancelled, repeated, "重复取消应返回同一订单快照");
            assertStock(fixture.products().getFirst().getId(), INITIAL_STOCK);
            assertEquals(firstSaved, savedOrder(created.getId()), "重复取消不能改写订单及取消时间");

            // 关键断言：库存更新 SQL 在两次调用中【总共只执行了 1 次】
            verify(inventorySpy, times(1)).update(isNull(), any(Wrapper.class));

            // 确认明细表没有被误删
            assertEquals(1L, orderItemMapper.selectCount(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, created.getId())));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "业务方法退出后，事务必须已完全提交关闭");
            passed = true;
        } finally {
            // 清理测试数据：如果 passed==true 则安全删除；如果挂了则保留数据方便排查
            finishFixture(fixture, passed);
        }
    }

    /**
     * 测试用例 2：并发取消订单（高并发场景核心测试）
     *
     * 验证目标：
     * 两个线程几乎在同一毫秒发起取消请求，底层 MySQL 行锁发挥互斥作用，
     * 必须且只能有【恰好一个线程成功将状态由 PENDING_PAYMENT 更新为 CANCELLED 并取得恢复库存资格（rows=1）】，
     * 另一个落败线程【执行条件更新受到 0 行影响（rows=0），走幂等逻辑返回原订单】，库存严格只被加回 1 次。
     *
     * 关键设计技巧：
     * 1. 为什么用 CyclicBarrier(2) 拦截在 orderSpy.update 内部？
     *    如果把屏障放在 orderService.cancel() 外面，因为线程启动、方法入栈和前置校验的微小耗时差异，
     *    两个线程真正发出 SQL 的时刻可能会错开几毫秒，导致一个执行完了另一个才开始，测不出真实行锁碰撞！
     *    插桩在 update 发出前，强行让两个真实业务事务在【临门一脚】前停下汇合，然后同时放行，100% 触发行锁竞争。
     *
     * 2. 为什么不能用 invocation.callRealMethod()？
     *    MyBatis 的 Mapper 接口（如 OrderMapper）是 JDK 动态代理（MapperProxy），没有默认方法体。
     *    Mockito 的 callRealMethod() 会报 AbstractMethodError！
     *    Spring 对其使用的是 delegatesTo 机制，因此需要取出 mockCreationSettings().getDefaultAnswer() 来触发真实代理。
     */
    @Test
    @SuppressWarnings("unchecked")
    void concurrentCancelRestoresStockExactlyOnce() throws Exception {
        assertServiceBoundary();
        Fixture fixture = createFixture(1);
        ExecutorService executor = null;
        List<Future<OrderVo>> futures = new ArrayList<>();
        boolean passed = false;
        try {
            OrderVo created = createPendingOrder(fixture);
            OrderMapper orderSpy = mapperObserver(orderMapper);
            ProductInventoryMapper inventorySpy = mapperObserver(inventoryMapper);
            clearInvocations(orderSpy, inventorySpy);

            // 获取真实 Mapper 代理的默认应答委托，用于执行底层真实数据库 SQL
            Answer<?> realOrderCalls = mockingDetails(orderSpy).getMockCreationSettings().getDefaultAnswer();
            CyclicBarrier updateBarrier = new CyclicBarrier(2);
            AtomicInteger changed = new AtomicInteger();   // 记录更新成功的次数（预期为 1）
            AtomicInteger unchanged = new AtomicInteger(); // 记录更新为0行的次数（预期为 1）

            // 利用 doAnswer 拦截在真实 SQL 执行前插入屏障
            doAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                        "条件更新必须在 Service 的真实事务中执行");
                // 两个线程到达此处时被挂起，只有两个都到了才同时放行，确保同时轰向 MySQL
                updateBarrier.await(10, TimeUnit.SECONDS);

                // 执行真实数据库 UPDATE: UPDATE orders SET status='CANCELLED'... WHERE status='PENDING_PAYMENT'
                int rows = (Integer) realOrderCalls.answer(invocation);
                assertTrue(rows == 0 || rows == 1, "受影响行数必须是 0 或 1");
                if (rows == 1) {
                    changed.incrementAndGet();
                } else {
                    unchanged.incrementAndGet();
                }
                return rows;
            }).when(orderSpy).update(isNull(), any(Wrapper.class));

            // 创建 2 个线程并发调用取消服务
            executor = Executors.newFixedThreadPool(2);
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> orderService.cancel(BUYER_ID, created.getId())));
            }

            // 两个线程无论谁先谁后，最终返回给客户端的 VO 必须都一致且为 CANCELLED 状态
            for (Future<OrderVo> future : futures) {
                assertCancelledSnapshot(created, future.get(20, TimeUnit.SECONDS));
            }

            // 【核心并发断言】
            assertEquals(1, changed.get(), "只能有一个请求取得恢复库存资格（受影响行数=1）");
            assertEquals(1, unchanged.get(), "另一个请求必须走已取消的幂等返回（受影响行数=0）");

            // 数据库层面验证：update 尝试了 2 次，但库存增加 SQL 只执行了 1 次
            verify(orderSpy, times(2)).update(isNull(), any(Wrapper.class));
            verify(inventorySpy, times(1)).update(isNull(), any(Wrapper.class));
            assertStock(fixture.products().getFirst().getId(), INITIAL_STOCK); // 恢复为初始 5，绝不能变成 7！

            OrderDo saved = savedOrder(created.getId());
            assertEquals(OrderStatus.CANCELLED, saved.getStatus());
            assertNotNull(saved.getCancelledAt());
            assertEquals(1L, orderMapper.selectCount(Wrappers.<OrderDo>lambdaQuery()
                    .eq(OrderDo::getBuyerId, BUYER_ID).eq(OrderDo::getRequestId, fixture.requestId())));
            assertEquals(1L, orderItemMapper.selectCount(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, created.getId())));
            passed = true;
        } finally {
            // 安全退出工人线程：必须等线程彻底终止后才能让 finally 的清理逻辑删库，防止数据竞争
            boolean stopped = stopWorkers(executor, futures);
            finishFixture(fixture, passed && stopped);
            assertTrue(stopped, "并发任务未及时结束，已保留本项专用数据");
        }
    }

    /**
     * 测试用例 3：多商品取消时，部分库存恢复失败触发全单物理回滚。
     *
     * 业务场景模拟：
     * 一个订单买了 2 个商品（商品 A 和商品 B）。在取消时：
     * 1. 订单状态更新为 CANCELLED；
     * 2. 商品 A 成功恢复库存（stock = 3 + 2 = 5）；
     * 3. 准备恢复商品 B 时，发现商品 B 的库存记录在数据库中意外丢失（affectedRows = 0）；
     * 4. 业务抛出 PRODUCT_INVENTORY_MISSING 异常。
     *
     * 验证重点（真实物理回滚）：
     * - 事务必须整体回滚！
     * - 订单状态必须从 CANCELLED 滚回 PENDING_PAYMENT，cancelled_at 必须恢复为 null！
     * - 刚才在第 2 步已经加回的商品 A 库存，必须从 5 物理撤销滚回到 3！
     */
    @Test
    @SuppressWarnings("unchecked")
    void missingSecondInventoryRollsBackStatusAndAlreadyRestoredStock() {
        assertServiceBoundary();
        Fixture fixture = createFixture(2); // 真实生成 2 个商品
        boolean passed = false;
        try {
            OrderVo created = createPendingOrder(fixture);
            Long firstProductId = fixture.products().getFirst().getId();
            Long secondProductId = fixture.products().get(1).getId();
            assertTrue(firstProductId < secondProductId, "验证顺序以商品ID递增为前提（防死锁排序）");
            OrderDo before = savedOrder(created.getId());

            // 【注入真实物理故障】：在测试事务外独立提交，删除第二个商品的库存记录
            // 这样在业务执行循环第 2 次 update 时，MySQL 会真实返回 0 行！
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    assertEquals(1, inventoryMapper.deleteById(secondProductId)));
            assertNull(inventoryMapper.selectById(secondProductId));

            ProductInventoryMapper inventorySpy = mapperObserver(inventoryMapper);
            clearInvocations(inventorySpy);
            Answer<?> realInventoryCalls = mockingDetails(inventorySpy).getMockCreationSettings().getDefaultAnswer();
            AtomicInteger restoreAttempts = new AtomicInteger();

            // 插桩观察：在事务中监控两次库存更新
            doAnswer(invocation -> {
                int attempt = restoreAttempts.incrementAndGet();
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                // 证明此时订单状态在当前事务内已经被改成了 CANCELLED
                assertEquals(OrderStatus.CANCELLED, savedOrder(created.getId()).getStatus(),
                        "注入观察点时，订单状态已经在当前事务内真实改变");
                int rows = (Integer) realInventoryCalls.answer(invocation);
                if (attempt == 1) {
                    assertEquals(1, rows, "第一个库存必须先真实恢复成功");
                    assertStock(firstProductId, INITIAL_STOCK); // 事务内此时看商品 A 是 5
                } else {
                    assertEquals(2, attempt);
                    assertEquals(0, rows, "第二个真实库存记录缺失，UPDATE 必须影响 0 行");
                    assertStock(firstProductId, INITIAL_STOCK);
                }
                return rows;
            }).when(inventorySpy).update(isNull(), any(Wrapper.class));

            // 调用取消，预期抛出库存缺失异常
            BusinessException failure = assertThrows(BusinessException.class,
                    () -> orderService.cancel(BUYER_ID, created.getId()));
            assertEquals(ErrorCode.PRODUCT_INVENTORY_MISSING, failure.getErrorCode());
            assertEquals(2, restoreAttempts.get(), "循环恢复库存确实执行到了第 2 次");
            verify(inventorySpy, times(2)).update(isNull(), any(Wrapper.class));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Service 抛出异常后，取消事务必须已经结束（已被回滚）");

            // ================= 【核心回滚断言】 =================
            // 1. 订单状态和时间字段必须完整回滚到取消前状态（PENDING_PAYMENT）
            assertEquals(before, savedOrder(created.getId()), "取消状态及时间字段必须完整回滚");
            // 2. 第一个商品的库存必须从 5 撤销回滚为 3！
            assertStock(firstProductId, INITIAL_STOCK - QUANTITY);
            // 3. 业务不能错误地把第二个已被删的库存给恢复出来
            assertNull(inventoryMapper.selectById(secondProductId), "取消回滚不能恢复事务前就已删除的库存记录");
            assertEquals(2L, orderItemMapper.selectCount(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, created.getId())), "原订单明细应保留");
            passed = true;
        } finally {
            finishFixture(fixture, passed);
        }
    }

    /** 两个真实业务事务在订单UPDATE前汇合，验证支付和取消只能赢得同一次状态转换。 */
    @Test
    @SuppressWarnings("unchecked")
    void concurrentPayAndCancelOnlyOneSucceeds() throws Exception {
        assertServiceBoundary();
        Fixture fixture = createFixture(1);
        ExecutorService executor = null;
        List<Future<OrderVo>> futures = new ArrayList<>();
        Long testedOrderId = null;
        boolean passed = false;
        try {
            OrderVo created = createPendingOrder(fixture);
            testedOrderId = created.getId();
            OrderDo original = savedOrder(created.getId());
            List<OrderItemDo> originalItems = orderItemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, created.getId()).orderByAsc(OrderItemDo::getId));
            assertEquals(1, originalItems.size());
            OrderMapper orderSpy = mapperObserver(orderMapper);
            ProductInventoryMapper inventorySpy = mapperObserver(inventoryMapper);
            clearInvocations(orderSpy, inventorySpy);

            Answer<?> realOrderCalls = mockingDetails(orderSpy).getMockCreationSettings().getDefaultAnswer();
            CyclicBarrier updateBarrier = new CyclicBarrier(2);
            AtomicInteger changed = new AtomicInteger();
            AtomicInteger unchanged = new AtomicInteger();
            doAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                // 在实际SQL之前同步，保留Mapper真实委托，不预设1/0或使用sleep猜测时序。
                updateBarrier.await(10, TimeUnit.SECONDS);
                int rows = (Integer) realOrderCalls.answer(invocation);
                assertTrue(rows == 0 || rows == 1);
                if (rows == 1) {
                    changed.incrementAndGet();
                } else {
                    unchanged.incrementAndGet();
                }
                return rows;
            }).when(orderSpy).update(isNull(), any(Wrapper.class));

            executor = Executors.newFixedThreadPool(2);
            futures.add(executor.submit(() -> orderService.pay(BUYER_ID, created.getId())));
            futures.add(executor.submit(() -> orderService.cancel(BUYER_ID, created.getId())));
            OrderVo winner = null;
            OrderOperationAction winningAction = null;
            int conflicts = 0;
            for (int index = 0; index < futures.size(); index++) {
                try {
                    OrderVo result = futures.get(index).get(20, TimeUnit.SECONDS);
                    assertNull(winner, "支付和取消不能同时成功");
                    winner = result;
                    winningAction = index == 0 ? OrderOperationAction.PAY : OrderOperationAction.CANCEL;
                } catch (ExecutionException exception) {
                    // 只接受明确的业务冲突，连接错误、超时和断言失败不能算预期的竞争落败。
                    BusinessException conflict = assertInstanceOf(BusinessException.class, exception.getCause());
                    assertEquals(ErrorCode.ORDER_CONFLICT, conflict.getErrorCode());
                    conflicts++;
                }
            }
            assertNotNull(winner);
            assertNotNull(winningAction);
            assertEquals(1, conflicts);
            assertEquals(1, changed.get());
            assertEquals(1, unchanged.get());
            verify(orderSpy, times(2)).update(isNull(), any(Wrapper.class));

            OrderDo saved = savedOrder(created.getId());
            assertEquals(winningAction.getToStatus(), saved.getStatus());
            assertEquals(saved.getStatus(), winner.getStatus());
            assertEquals(created.getId(), winner.getId());
            assertEquals(created.getOrderNo(), winner.getOrderNo());
            assertEquals(created.getTotalAmount(), winner.getTotalAmount());
            assertEquals(created.getCreatedAt(), winner.getCreatedAt());
            assertEquals(created.getExpiresAt(), winner.getExpiresAt());
            assertEquals(created.getItems(), winner.getItems());
            assertEquals(original.getOrderNo(), saved.getOrderNo());
            assertEquals(original.getBuyerId(), saved.getBuyerId());
            assertEquals(original.getRequestId(), saved.getRequestId());
            assertEquals(original.getRequestFingerprint(), saved.getRequestFingerprint());
            assertEquals(original.getTotalAmount(), saved.getTotalAmount());
            assertEquals(original.getCreatedAt(), saved.getCreatedAt());
            assertEquals(original.getExpiresAt(), saved.getExpiresAt());
            int expectedStock;
            if (winningAction == OrderOperationAction.PAY) {
                expectedStock = INITIAL_STOCK - QUANTITY;
                assertNotNull(saved.getPaidAt());
                assertTrue(saved.getPaidAt().isBefore(saved.getExpiresAt()));
                assertNull(saved.getCancelledAt());
                assertEquals(saved.getPaidAt(), saved.getUpdatedAt());
                verify(inventorySpy, times(0)).update(isNull(), any(Wrapper.class));
            } else {
                expectedStock = INITIAL_STOCK;
                assertNotNull(saved.getCancelledAt());
                assertNull(saved.getPaidAt());
                assertEquals(saved.getCancelledAt(), saved.getUpdatedAt());
                verify(inventorySpy, times(1)).update(isNull(), any(Wrapper.class));
            }
            assertStock(fixture.products().getFirst().getId(), expectedStock);
            assertEquals(originalItems, orderItemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, created.getId()).orderByAsc(OrderItemDo::getId)));
            List<OrderOperationLogDo> operations = operationLogMapper.selectList(Wrappers.<OrderOperationLogDo>lambdaQuery()
                    .eq(OrderOperationLogDo::getOrderId, created.getId())
                    .orderByAsc(OrderOperationLogDo::getCreatedAt, OrderOperationLogDo::getId));
            assertEquals(List.of(OrderOperationAction.CREATE, winningAction),
                    operations.stream().map(OrderOperationLogDo::getAction).toList());
            for (OrderOperationLogDo operation : operations) {
                assertNotNull(operation.getId());
                assertEquals(created.getId(), operation.getOrderId());
                assertEquals(BUYER_ID, operation.getOperatorId());
            }
            assertNull(operations.getFirst().getFromStatus());
            assertEquals(OrderStatus.PENDING_PAYMENT, operations.getFirst().getToStatus());
            assertEquals(created.getCreatedAt(), operations.getFirst().getCreatedAt());
            assertEquals(OrderStatus.PENDING_PAYMENT, operations.get(1).getFromStatus());
            assertEquals(saved.getStatus(), operations.get(1).getToStatus());
            assertEquals(saved.getUpdatedAt(), operations.get(1).getCreatedAt());
            assertEquals(1L, orderMapper.selectCount(Wrappers.<OrderDo>lambdaQuery()
                    .eq(OrderDo::getBuyerId, BUYER_ID).eq(OrderDo::getRequestId, fixture.requestId())));
            System.out.printf("PAY_CANCEL winner=%s, updateRows=1/0, stock=%d, actions=CREATE/%s%n",
                    winningAction, expectedStock, winningAction);
            passed = true;
        } finally {
            // 工作线程必须真正退出，才可删除本次数据；失败或未终止时保留现场。
            boolean stopped = stopWorkers(executor, futures);
            finishFixture(fixture, passed && stopped);
            assertTrue(stopped, "并发任务未及时结束，已保留本项专用数据");
        }
        assertNotNull(testedOrderId);
        assertNull(orderMapper.selectById(testedOrderId));
        assertEquals(0L, orderItemMapper.selectCount(Wrappers.<OrderItemDo>lambdaQuery()
                .eq(OrderItemDo::getOrderId, testedOrderId)));
        assertEquals(0L, operationLogMapper.selectCount(Wrappers.<OrderOperationLogDo>lambdaQuery()
                .eq(OrderOperationLogDo::getOrderId, testedOrderId)));
        for (ProductDo product : fixture.products()) {
            assertNull(inventoryMapper.selectById(product.getId()));
            assertNull(productMapper.selectById(product.getId()));
        }
        System.out.println("PAY_CANCEL fixture cleanup verified after both workers terminated");
    }

    /** 已过期订单的取消与系统关闭竞争，只能由获胜事务恢复库存并写成功流水。 */
    @Test
    @SuppressWarnings("unchecked")
    void concurrentCancelAndCloseRestoresStockExactlyOnce() throws Exception {
        assertServiceBoundary();
        assertTrue(AopUtils.isAopProxy(closeTransactionService));
        Fixture fixture = createFixture(1);
        ExecutorService executor = null;
        List<Future<?>> futures = new ArrayList<>();
        Long testedOrderId = null;
        boolean passed = false;
        try {
            OrderVo created = createPendingOrder(fixture);
            Long orderId = created.getId();
            testedOrderId = orderId;
            LocalDateTime historicalCreatedAt = prepareExpiredOrder(fixture, orderId);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            OrderDo original = savedOrder(orderId);
            assertEquals(OrderStatus.PENDING_PAYMENT, original.getStatus());
            assertTrue(original.getExpiresAt().isBefore(LocalDateTime.now()));
            assertStock(fixture.products().getFirst().getId(), INITIAL_STOCK - QUANTITY);
            List<OrderItemDo> originalItems = orderItemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, orderId).orderByAsc(OrderItemDo::getId));
            OrderMapper orderSpy = mapperObserver(orderMapper);
            ProductInventoryMapper inventorySpy = mapperObserver(inventoryMapper);
            // 历史时间准备中的UPDATE不计入业务竞争的两次状态更新。
            clearInvocations(orderSpy, inventorySpy);
            Answer<?> realOrderCalls = mockingDetails(orderSpy).getMockCreationSettings().getDefaultAnswer();
            CyclicBarrier updateBarrier = new CyclicBarrier(2);
            AtomicInteger changed = new AtomicInteger();
            AtomicInteger unchanged = new AtomicInteger();
            doAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                updateBarrier.await(10, TimeUnit.SECONDS);
                int rows = (Integer) realOrderCalls.answer(invocation);
                assertTrue(rows == 0 || rows == 1);
                if (rows == 1) {
                    changed.incrementAndGet();
                } else {
                    unchanged.incrementAndGet();
                }
                return rows;
            }).when(orderSpy).update(isNull(), any(Wrapper.class));

            executor = Executors.newFixedThreadPool(2);
            Future<OrderVo> cancelFuture = executor.submit(() -> orderService.cancel(BUYER_ID, orderId));
            futures.add(cancelFuture);
            Future<Boolean> closeFuture = executor.submit(() -> closeTransactionService.closeExpiredOrder(orderId));
            futures.add(closeFuture);
            OrderVo cancelled = null;
            BusinessException cancelConflict = null;
            try {
                cancelled = cancelFuture.get(20, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                cancelConflict = assertInstanceOf(BusinessException.class, exception.getCause());
                assertEquals(ErrorCode.ORDER_CONFLICT, cancelConflict.getErrorCode());
            }
            boolean closed = closeFuture.get(20, TimeUnit.SECONDS);
            assertEquals(1, changed.get());
            assertEquals(1, unchanged.get());
            verify(orderSpy, times(2)).update(isNull(), any(Wrapper.class));
            verify(inventorySpy, times(1)).update(isNull(), any(Wrapper.class));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            OrderDo saved = savedOrder(orderId);
            OrderOperationAction winningAction;
            Long winningOperator;
            if (closed) {
                assertNull(cancelled, "关闭成功后取消不能成功返回");
                assertNotNull(cancelConflict);
                assertEquals(OrderStatus.CLOSED, saved.getStatus());
                assertNull(saved.getCancelledAt());
                winningAction = OrderOperationAction.CLOSE;
                winningOperator = 0L;
            } else {
                assertNotNull(cancelled, "关闭跳过时必须由取消成功完成转换");
                assertNull(cancelConflict);
                assertEquals(OrderStatus.CANCELLED, saved.getStatus());
                assertNotNull(saved.getCancelledAt());
                assertEquals(saved.getCancelledAt(), saved.getUpdatedAt());
                assertEquals(orderId, cancelled.getId());
                assertEquals(saved.getStatus(), cancelled.getStatus());
                assertEquals(original.getOrderNo(), cancelled.getOrderNo());
                assertEquals(original.getTotalAmount(), cancelled.getTotalAmount());
                assertEquals(original.getCreatedAt(), cancelled.getCreatedAt());
                assertEquals(original.getExpiresAt(), cancelled.getExpiresAt());
                assertEquals(created.getItems(), cancelled.getItems());
                winningAction = OrderOperationAction.CANCEL;
                winningOperator = BUYER_ID;
            }
            assertNull(saved.getPaidAt());
            assertNotNull(saved.getUpdatedAt());
            assertFalse(saved.getUpdatedAt().isBefore(original.getExpiresAt()));
            assertEquals(original.getOrderNo(), saved.getOrderNo());
            assertEquals(original.getBuyerId(), saved.getBuyerId());
            assertEquals(original.getRequestId(), saved.getRequestId());
            assertEquals(original.getRequestFingerprint(), saved.getRequestFingerprint());
            assertEquals(original.getTotalAmount(), saved.getTotalAmount());
            assertEquals(original.getCreatedAt(), saved.getCreatedAt());
            assertEquals(original.getExpiresAt(), saved.getExpiresAt());
            assertStock(fixture.products().getFirst().getId(), INITIAL_STOCK);
            assertEquals(originalItems, orderItemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, orderId).orderByAsc(OrderItemDo::getId)));
            List<OrderOperationLogDo> operations = operationLogMapper.selectList(Wrappers.<OrderOperationLogDo>lambdaQuery()
                    .eq(OrderOperationLogDo::getOrderId, orderId)
                    .orderByAsc(OrderOperationLogDo::getCreatedAt, OrderOperationLogDo::getId));
            assertEquals(List.of(OrderOperationAction.CREATE, winningAction),
                    operations.stream().map(OrderOperationLogDo::getAction).toList());
            assertEquals(BUYER_ID, operations.getFirst().getOperatorId());
            assertEquals(historicalCreatedAt, operations.getFirst().getCreatedAt());
            assertNull(operations.getFirst().getFromStatus());
            assertEquals(OrderStatus.PENDING_PAYMENT, operations.getFirst().getToStatus());
            assertEquals(orderId, operations.get(1).getOrderId());
            assertNotNull(operations.get(1).getId());
            assertEquals(winningOperator, operations.get(1).getOperatorId());
            assertEquals(OrderStatus.PENDING_PAYMENT, operations.get(1).getFromStatus());
            assertEquals(saved.getStatus(), operations.get(1).getToStatus());
            assertEquals(saved.getUpdatedAt(), operations.get(1).getCreatedAt());
            System.out.printf("CANCEL_CLOSE winner=%s, closeResult=%s, updateRows=1/0, stock=5, restores=1%n",
                    winningAction, closed);
            passed = true;
        } finally {
            boolean stopped = stopWorkers(executor, futures);
            finishFixture(fixture, passed && stopped);
            assertTrue(stopped, "并发任务未及时结束，已保留本项专用数据");
        }
        assertNotNull(testedOrderId);
        assertNull(orderMapper.selectById(testedOrderId));
        assertEquals(0L, orderItemMapper.selectCount(Wrappers.<OrderItemDo>lambdaQuery()
                .eq(OrderItemDo::getOrderId, testedOrderId)));
        assertEquals(0L, operationLogMapper.selectCount(Wrappers.<OrderOperationLogDo>lambdaQuery()
                .eq(OrderOperationLogDo::getOrderId, testedOrderId)));
        for (ProductDo product : fixture.products()) {
            assertNull(inventoryMapper.selectById(product.getId()));
            assertNull(productMapper.selectById(product.getId()));
        }
        System.out.println("CANCEL_CLOSE fixture cleanup verified after both workers terminated");
    }

    /** 两件商品中第二份库存缺失，关闭必须撤销先前已成功恢复的库存与状态。 */
    @Test
    @SuppressWarnings("unchecked")
    void missingSecondInventoryDuringCloseRollsBackStateAndFirstRestore() {
        assertServiceBoundary();
        assertTrue(AopUtils.isAopProxy(closeTransactionService));
        Fixture fixture = createFixture(2);
        boolean passed = false;
        try {
            Long orderId = createPendingOrder(fixture).getId();
            prepareExpiredOrder(fixture, orderId);
            OrderDo before = savedOrder(orderId);
            Long firstId = fixture.products().getFirst().getId();
            Long secondId = fixture.products().get(1).getId();
            assertTrue(firstId < secondId);
            List<OrderItemDo> originalItems = orderItemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, orderId).orderByAsc(OrderItemDo::getId));
            List<OrderOperationLogDo> originalOperations = operationLogMapper.selectList(Wrappers.<OrderOperationLogDo>lambdaQuery()
                    .eq(OrderOperationLogDo::getOrderId, orderId).orderByAsc(OrderOperationLogDo::getId));
            assertEquals(1, originalOperations.size());
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    assertEquals(1, inventoryMapper.deleteById(secondId)));
            ProductInventoryMapper observer = mapperObserver(inventoryMapper);
            clearInvocations(observer);
            Answer<?> realCalls = mockingDetails(observer).getMockCreationSettings().getDefaultAnswer();
            AtomicInteger attempts = new AtomicInteger();
            doAnswer(invocation -> {
                int attempt = attempts.incrementAndGet();
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(OrderStatus.CLOSED, savedOrder(orderId).getStatus());
                int rows = (Integer) realCalls.answer(invocation);
                assertEquals(attempt == 1 ? 1 : 0, rows);
                assertTrue(attempt <= 2);
                assertStock(firstId, INITIAL_STOCK);
                return rows;
            }).when(observer).update(isNull(), any(Wrapper.class));
            BusinessException failure = assertThrows(BusinessException.class,
                    () -> closeTransactionService.closeExpiredOrder(orderId));
            assertEquals(ErrorCode.PRODUCT_INVENTORY_MISSING, failure.getErrorCode());
            assertEquals(2, attempts.get());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(before, savedOrder(orderId));
            assertStock(firstId, INITIAL_STOCK - QUANTITY);
            assertNull(inventoryMapper.selectById(secondId), "业务回滚不能恢复准备阶段已删除的记录");
            assertEquals(originalItems, orderItemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, orderId).orderByAsc(OrderItemDo::getId)));
            assertEquals(originalOperations, operationLogMapper.selectList(Wrappers.<OrderOperationLogDo>lambdaQuery()
                    .eq(OrderOperationLogDo::getOrderId, orderId).orderByAsc(OrderOperationLogDo::getId)));
            passed = true;
        } finally {
            finishFixture(fixture, passed);
        }
    }

    /** 重复的系统关闭同时触发，也只能关闭一次、恢复库存一次并生成一条CLOSE。 */
    @Test
    @SuppressWarnings("unchecked")
    void concurrentCloseRestoresStockExactlyOnce() throws Exception {
        assertServiceBoundary();
        assertTrue(AopUtils.isAopProxy(closeTransactionService));
        Fixture fixture = createFixture(1);
        ExecutorService executor = null;
        List<Future<Boolean>> futures = new ArrayList<>();
        Long testedOrderId = null;
        boolean passed = false;
        try {
            Long orderId = createPendingOrder(fixture).getId();
            testedOrderId = orderId;
            LocalDateTime historicalCreatedAt = prepareExpiredOrder(fixture, orderId);
            OrderDo original = savedOrder(orderId);
            List<OrderItemDo> originalItems = orderItemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, orderId).orderByAsc(OrderItemDo::getId));
            OrderMapper orderSpy = mapperObserver(orderMapper);
            ProductInventoryMapper inventorySpy = mapperObserver(inventoryMapper);
            clearInvocations(orderSpy, inventorySpy);
            Answer<?> realCalls = mockingDetails(orderSpy).getMockCreationSettings().getDefaultAnswer();
            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicInteger changed = new AtomicInteger();
            AtomicInteger unchanged = new AtomicInteger();
            doAnswer(invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                barrier.await(10, TimeUnit.SECONDS);
                int rows = (Integer) realCalls.answer(invocation);
                assertTrue(rows == 0 || rows == 1);
                if (rows == 1) { changed.incrementAndGet(); } else { unchanged.incrementAndGet(); }
                return rows;
            }).when(orderSpy).update(isNull(), any(Wrapper.class));
            executor = Executors.newFixedThreadPool(2);
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> closeTransactionService.closeExpiredOrder(orderId)));
            }
            int closed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(20, TimeUnit.SECONDS)) { closed++; }
            }
            assertEquals(1, closed);
            assertEquals(1, changed.get());
            assertEquals(1, unchanged.get());
            verify(orderSpy, times(2)).update(isNull(), any(Wrapper.class));
            verify(inventorySpy, times(1)).update(isNull(), any(Wrapper.class));
            OrderDo saved = savedOrder(orderId);
            assertEquals(OrderStatus.CLOSED, saved.getStatus());
            assertNull(saved.getPaidAt());
            assertNull(saved.getCancelledAt());
            assertEquals(original.getOrderNo(), saved.getOrderNo());
            assertEquals(original.getBuyerId(), saved.getBuyerId());
            assertEquals(original.getTotalAmount(), saved.getTotalAmount());
            assertEquals(original.getCreatedAt(), saved.getCreatedAt());
            assertEquals(original.getExpiresAt(), saved.getExpiresAt());
            assertStock(fixture.products().getFirst().getId(), INITIAL_STOCK);
            assertEquals(originalItems, orderItemMapper.selectList(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, orderId).orderByAsc(OrderItemDo::getId)));
            List<OrderOperationLogDo> operations = operationLogMapper.selectList(Wrappers.<OrderOperationLogDo>lambdaQuery()
                    .eq(OrderOperationLogDo::getOrderId, orderId)
                    .orderByAsc(OrderOperationLogDo::getCreatedAt, OrderOperationLogDo::getId));
            assertEquals(List.of(OrderOperationAction.CREATE, OrderOperationAction.CLOSE),
                    operations.stream().map(OrderOperationLogDo::getAction).toList());
            assertEquals(historicalCreatedAt, operations.getFirst().getCreatedAt());
            assertEquals(0L, operations.get(1).getOperatorId());
            assertEquals(OrderStatus.PENDING_PAYMENT, operations.get(1).getFromStatus());
            assertEquals(OrderStatus.CLOSED, operations.get(1).getToStatus());
            assertEquals(saved.getUpdatedAt(), operations.get(1).getCreatedAt());
            passed = true;
        } finally {
            boolean stopped = stopWorkers(executor, futures);
            finishFixture(fixture, passed && stopped);
            assertTrue(stopped, "关闭线程未结束，保留专用数据");
        }
        assertNotNull(testedOrderId);
        assertNull(orderMapper.selectById(testedOrderId));
        assertEquals(0L, operationLogMapper.selectCount(Wrappers.<OrderOperationLogDo>lambdaQuery()
                .eq(OrderOperationLogDo::getOrderId, testedOrderId)));
        assertEquals(0L, orderItemMapper.selectCount(Wrappers.<OrderItemDo>lambdaQuery()
                .eq(OrderItemDo::getOrderId, testedOrderId)));
        for (ProductDo product : fixture.products()) {
            assertNull(inventoryMapper.selectById(product.getId()));
            assertNull(productMapper.selectById(product.getId()));
        }
    }

    /** 仅把本次专用订单准备为历史过期状态，不改变生产时间配置。 */
    private LocalDateTime prepareExpiredOrder(Fixture fixture, Long orderId) {
        LocalDateTime createdAt = LocalDateTime.now().withNano(0).minusMinutes(31);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertEquals(1, orderMapper.update(null, Wrappers.<OrderDo>lambdaUpdate()
                    .eq(OrderDo::getId, orderId).eq(OrderDo::getBuyerId, BUYER_ID)
                    .eq(OrderDo::getStatus, OrderStatus.PENDING_PAYMENT)
                    .set(OrderDo::getCreatedAt, createdAt).set(OrderDo::getUpdatedAt, createdAt)
                    .set(OrderDo::getExpiresAt, createdAt.plusMinutes(30))));
            assertEquals(fixture.products().size(), orderItemMapper.update(null, Wrappers.<OrderItemDo>lambdaUpdate()
                    .eq(OrderItemDo::getOrderId, orderId)
                    .set(OrderItemDo::getCreatedAt, createdAt).set(OrderItemDo::getUpdatedAt, createdAt)));
            assertEquals(1, operationLogMapper.update(null, Wrappers.<OrderOperationLogDo>lambdaUpdate()
                    .eq(OrderOperationLogDo::getOrderId, orderId)
                    .eq(OrderOperationLogDo::getAction, OrderOperationAction.CREATE)
                    .set(OrderOperationLogDo::getCreatedAt, createdAt)));
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        return createdAt;
    }

    private void assertServiceBoundary() {
        assertTrue(AopUtils.isAopProxy(orderService), "必须通过Spring事务代理调用Service");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "测试本身不能开启事务");
        // 先确认两个Mapper的Spy都能定位，再准备真实数据，避免配置错误留下无用fixture。
        mapperObserver(orderMapper);
        mapperObserver(inventoryMapper);
    }

    /**
     * Spring会把JDK Mapper代理包装成默认委托给原对象的Mock，isMock为true而isSpy为false。
     * 因此检查Mockito包装而非isSpy，并在找到包装后停止解包；保留默认应答执行真实SQL。
     * 此处只定位配置/验证对象，业务入口始终调用注入的orderService事务代理。
     */
    private static <T> T mapperObserver(T bean) {
        T spy = bean;
        while (!mockingDetails(spy).isMock()) {
            T target = AopTestUtils.getTargetObject(spy);
            assertNotSame(spy, target, "Mapper代理链中未找到Mockito包装，请检查@MockitoSpyBean配置");
            spy = target;
        }
        return spy;
    }

    private Fixture createFixture(int productCount) {
        List<ProductDo> products = new ArrayList<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int i = 0; i < productCount; i++) {
                ProductDo product = new ProductDo();
                product.setSku("CANCEL-IT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
                product.setName("取消订单集成测试商品");
                product.setPrice(new BigDecimal("12.50"));
                product.setStatus(ProductStatus.ON_SALE);
                assertEquals(1, productMapper.insert(product));
                assertNotNull(product.getId());
                ProductInventoryDo inventory = new ProductInventoryDo();
                inventory.setProductId(product.getId());
                inventory.setStock(INITIAL_STOCK);
                assertEquals(1, inventoryMapper.insert(inventory));
                products.add(product);
            }
        });
        return new Fixture(UUID.randomUUID().toString(), List.copyOf(products));
    }

    private OrderVo createPendingOrder(Fixture fixture) {
        List<CreateOrderItemRequest> items = new ArrayList<>();
        for (ProductDo product : fixture.products().reversed()) {
            CreateOrderItemRequest item = new CreateOrderItemRequest();
            item.setProductId(product.getId());
            item.setQuantity(QUANTITY);
            items.add(item);
        }
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestId(fixture.requestId());
        request.setItems(items);
        OrderVo created = orderService.create(BUYER_ID, request);
        assertNotNull(created.getId());
        assertEquals(OrderStatus.PENDING_PAYMENT, created.getStatus());
        assertEquals(OrderStatus.PENDING_PAYMENT, savedOrder(created.getId()).getStatus());
        assertNull(savedOrder(created.getId()).getCancelledAt());
        for (ProductDo product : fixture.products()) {
            assertStock(product.getId(), INITIAL_STOCK - QUANTITY);
        }
        return created;
    }

    private static void assertCancelledSnapshot(OrderVo created, OrderVo cancelled) {
        assertEquals(created.getId(), cancelled.getId());
        assertEquals(created.getOrderNo(), cancelled.getOrderNo());
        assertEquals(OrderStatus.CANCELLED, cancelled.getStatus());
        assertEquals(created.getTotalAmount(), cancelled.getTotalAmount());
        assertEquals(created.getCreatedAt(), cancelled.getCreatedAt());
        assertEquals(created.getExpiresAt(), cancelled.getExpiresAt());
        assertEquals(created.getItems(), cancelled.getItems(), "取消不能改写下单时的商品快照");
    }

    private OrderDo savedOrder(Long orderId) {
        OrderDo order = orderMapper.selectById(orderId);
        assertNotNull(order);
        return order;
    }

    private void assertStock(Long productId, int expected) {
        ProductInventoryDo inventory = inventoryMapper.selectById(productId);
        assertNotNull(inventory);
        assertEquals(expected, inventory.getStock().intValue(), "商品" + productId + "库存不符合预期");
    }

    // 终止逻辑不依赖返回值，兼容订单结果和关闭方法的布尔结果。
    private static boolean stopWorkers(ExecutorService executor, List<? extends Future<?>> futures) {
        if (executor == null) {
            return true;
        }
        for (Future<?> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
        executor.shutdownNow();
        try {
            return executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void finishFixture(Fixture fixture, boolean passed) {
        if (!passed) {
            System.err.printf("取消测试未通过，保留专用数据：requestId=%s, productIds=%s%n",
                    fixture.requestId(), fixture.products().stream().map(ProductDo::getId).toList());
            return;
        }
        // 只清理本项随机请求及随机商品；清理本身也在一个事务内，避免留下半套数据。
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            List<OrderDo> orders = orderMapper.selectList(Wrappers.<OrderDo>lambdaQuery()
                    .eq(OrderDo::getBuyerId, BUYER_ID).eq(OrderDo::getRequestId, fixture.requestId()));
            assertEquals(1, orders.size());
            Long orderId = orders.getFirst().getId();
            assertEquals(fixture.products().size(), orderItemMapper.delete(Wrappers.<OrderItemDo>lambdaQuery()
                    .eq(OrderItemDo::getOrderId, orderId)));
            operationLogMapper.delete(Wrappers.<OrderOperationLogDo>lambdaQuery().eq(OrderOperationLogDo::getOrderId, orderId));
            assertEquals(1, orderMapper.deleteById(orderId));
            for (ProductDo product : fixture.products()) {
                inventoryMapper.deleteById(product.getId());
                assertEquals(1, productMapper.deleteById(product.getId()));
            }
        });
    }

    private record Fixture(String requestId, List<ProductDo> products) {
    }
}
