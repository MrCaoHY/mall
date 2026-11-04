package com.chy.mall;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.chy.mall.dto.UpdateProductRequest;
import com.chy.mall.dto.UpdateProductStatusRequest;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.service.impl.ProductServiceImpl;
import com.chy.mall.vo.ProductVo;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 阶段二核心测试：商品详情 Cache-Aside 缓存读写与事务后双写一致性失效。
 */
class ProductCacheServiceTest {

    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final ProductInventoryMapper inventoryMapper = mock(ProductInventoryMapper.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProductServiceImpl service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "product-cache-test");
        assistant.setCurrentNamespace(ProductMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, ProductDo.class);
    }

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        service = new ProductServiceImpl(productMapper, inventoryMapper, redisTemplate, objectMapper);
    }

    /**
     * 场景 1：缓存未命中（Cache Miss）
     * 预期：查数据库、组装结果、回写 Redis（带有基础 30 分钟 + 随机抖动的 TTL）。
     */
    @Test
    void getByIdCacheMissQueriesDbAndSavesToCache() {
        Long productId = 100L;
        String cacheKey = "mall:product:detail:" + productId;

        // Redis 中无此缓存
        when(valueOps.get(cacheKey)).thenReturn(null);

        // 数据库中有数据
        ProductDo product = createProductDo(productId, "机械键盘", new BigDecimal("199.00"));
        ProductInventoryDo inventory = createInventoryDo(productId, 50);
        when(productMapper.selectById(productId)).thenReturn(product);
        when(inventoryMapper.selectById(productId)).thenReturn(inventory);

        ProductVo vo = service.getById(productId);

        assertNotNull(vo);
        assertEquals(productId, vo.getId());
        assertEquals("机械键盘", vo.getName());
        assertEquals(50, vo.getStock());

        // 验证查了数据库
        verify(productMapper, times(1)).selectById(productId);
        verify(inventoryMapper, times(1)).selectById(productId);

        // 验证回写了 Redis，且 TTL 在 30 分钟到 35 分钟之间
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps, times(1)).set(eq(cacheKey), anyString(), ttlCaptor.capture());
        Duration capturedTtl = ttlCaptor.getValue();
        assertTrue(capturedTtl.toMinutes() >= 30 && capturedTtl.toMinutes() <= 35,
                "TTL 必须包含 30 分钟基础值与 0~300 秒随机打散防雪崩");
    }

    /**
     * 场景 2：缓存命中（Cache Hit，核心收益点）
     * 预期：直接反序列化返回，绝对不调用数据库 Mapper。
     */
    @Test
    void getByIdCacheHitReturnsFromRedisWithoutTouchingDb() throws Exception {
        Long productId = 100L;
        String cacheKey = "mall:product:detail:" + productId;

        // Redis 中已命中缓存
        ProductVo cachedVo = new ProductVo();
        cachedVo.setId(productId);
        cachedVo.setSku("KEYBOARD-001");
        cachedVo.setName("缓存中的机械键盘");
        cachedVo.setPrice(new BigDecimal("189.00"));
        cachedVo.setStatus(ProductStatus.ON_SALE);
        cachedVo.setStock(99);
        String cachedJson = objectMapper.writeValueAsString(cachedVo);

        when(valueOps.get(cacheKey)).thenReturn(cachedJson);

        ProductVo vo = service.getById(productId);

        assertNotNull(vo);
        assertEquals(productId, vo.getId());
        assertEquals("缓存中的机械键盘", vo.getName());
        assertEquals(99, vo.getStock());

        // 核心断言：绝对没有触碰数据库！
        verify(productMapper, never()).selectById(any());
        verify(inventoryMapper, never()).selectById(any());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    /**
     * 场景 3：Redis 读异常容灾降级（Fault-tolerant Fallback）
     * 预期：Redis 超时或报错时不阻断用户，优雅降级走数据库。
     */
    @Test
    void getByIdRedisReadErrorFallsBackToDbGracefully() {
        Long productId = 101L;
        String cacheKey = "mall:product:detail:" + productId;

        // 模拟 Redis 挂了或网络超时
        when(valueOps.get(cacheKey)).thenThrow(new RuntimeException("Redis connection timeout"));

        ProductDo product = createProductDo(productId, "降级商品", new BigDecimal("59.00"));
        ProductInventoryDo inventory = createInventoryDo(productId, 10);
        when(productMapper.selectById(productId)).thenReturn(product);
        when(inventoryMapper.selectById(productId)).thenReturn(inventory);

        // 业务方法不能抛出异常
        ProductVo vo = assertDoesNotThrow(() -> service.getById(productId));

        assertNotNull(vo);
        assertEquals("降级商品", vo.getName());
        // 成功降级查库
        verify(productMapper, times(1)).selectById(productId);
    }

    /**
     * 场景 4：Redis 写异常不阻断主流程（Write Resilience）
     * 预期：写缓存失败仅记录告警，依然给前端返回商品数据。
     */
    @Test
    void getByIdRedisWriteErrorDoesNotBreakQuery() {
        Long productId = 102L;
        String cacheKey = "mall:product:detail:" + productId;

        when(valueOps.get(cacheKey)).thenReturn(null);
        doThrow(new RuntimeException("Redis OOM")).when(valueOps).set(eq(cacheKey), anyString(), any(Duration.class));

        ProductDo product = createProductDo(productId, "容错商品", new BigDecimal("79.00"));
        ProductInventoryDo inventory = createInventoryDo(productId, 20);
        when(productMapper.selectById(productId)).thenReturn(product);
        when(inventoryMapper.selectById(productId)).thenReturn(inventory);

        ProductVo vo = assertDoesNotThrow(() -> service.getById(productId));

        assertNotNull(vo);
        assertEquals("容错商品", vo.getName());
    }

    /**
     * 场景 5：更新商品资料时在事务提交后精准删除缓存（Evict Cache on Commit）
     * 预期：update 操作完成且事务 COMMIT 后，触发 stringRedisTemplate.delete。
     */
    @Test
    void updateEvictsCacheAfterTransactionCommit() {
        Long productId = 103L;
        String cacheKey = "mall:product:detail:" + productId;

        UpdateProductRequest request = new UpdateProductRequest();
        request.setName("改名后的键盘");
        request.setPrice(new BigDecimal("169.00"));

        when(productMapper.update(isNull(), any())).thenReturn(1);
        ProductDo updatedDo = createProductDo(productId, "改名后的键盘", new BigDecimal("169.00"));
        ProductInventoryDo inventory = createInventoryDo(productId, 30);
        when(productMapper.selectById(productId)).thenReturn(updatedDo);
        when(inventoryMapper.selectById(productId)).thenReturn(inventory);

        // 模拟 Spring 事务上下文已激活
        TransactionSynchronizationManager.initSynchronization();
        try {
            ProductVo vo = service.update(productId, request);
            assertNotNull(vo);
            assertEquals("改名后的键盘", vo.getName());

            // 尚未 commit 前，缓存不能被删除（防止并发脏读）
            verify(redisTemplate, never()).delete(cacheKey);

            // 模拟事务提交完成，触发 afterCommit 回调
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertFalse(synchronizations.isEmpty(), "必须注册事务同步器");
            for (TransactionSynchronization sync : synchronizations) {
                sync.afterCommit();
            }

            // 事务提交后，精准删除缓存
            verify(redisTemplate, times(1)).delete(cacheKey);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 场景 6：更新上下架状态在事务提交后精准删除缓存
     */
    @Test
    void updateStatusEvictsCacheAfterTransactionCommit() {
        Long productId = 104L;
        String cacheKey = "mall:product:detail:" + productId;

        UpdateProductStatusRequest request = new UpdateProductStatusRequest();
        request.setStatus(ProductStatus.OFF_SALE);

        when(productMapper.update(isNull(), any())).thenReturn(1);
        ProductDo product = createProductDo(productId, "下架商品", new BigDecimal("99.00"));
        product.setStatus(ProductStatus.OFF_SALE);
        ProductInventoryDo inventory = createInventoryDo(productId, 10);
        when(productMapper.selectById(productId)).thenReturn(product);
        when(inventoryMapper.selectById(productId)).thenReturn(inventory);

        TransactionSynchronizationManager.initSynchronization();
        try {
            ProductVo vo = service.updateStatus(productId, request);
            assertNotNull(vo);
            assertEquals(ProductStatus.OFF_SALE, vo.getStatus());

            // 触发 commit
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }

            verify(redisTemplate, times(1)).delete(cacheKey);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 场景 7：防缓存穿透 —— 查询不存在的商品时向 Redis 写入 EMPTY 哨兵并设置 2 分钟短 TTL
     */
    @Test
    void getByIdNotFoundSavesEmptyCacheToRedis() {
        Long productId = 999L;
        String cacheKey = "mall:product:detail:" + productId;

        // 缓存未命中，数据库也查无此商品
        when(valueOps.get(cacheKey)).thenReturn(null);
        when(productMapper.selectById(productId)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.getById(productId));
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, ex.getErrorCode());

        // 核心验证：必须向 Redis 写入 "EMPTY"，且 TTL 为 2 分钟防穿透
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps, times(1)).set(eq(cacheKey), eq("EMPTY"), ttlCaptor.capture());
        assertEquals(Duration.ofMinutes(2), ttlCaptor.getValue(), "空值缓存 TTL 必须严格为 2 分钟");
    }

    /**
     * 场景 8：防缓存穿透 —— 命中了 EMPTY 哨兵缓存时直接抛出 404，绝不触碰 MySQL
     */
    @Test
    void getByIdEmptyCacheHitThrows404WithoutTouchingDb() {
        Long productId = 999L;
        String cacheKey = "mall:product:detail:" + productId;

        // Redis 中已存在之前写入的空值哨兵
        when(valueOps.get(cacheKey)).thenReturn("EMPTY");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.getById(productId));
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, ex.getErrorCode());

        // 核心验证：绝对没有查询数据库！MySQL 受到 100% 保护
        verify(productMapper, never()).selectById(any());
        verify(inventoryMapper, never()).selectById(any());
    }

    /**
     * 场景 9：商品创建在事务提交后清理潜在的空值缓存
     */
    @Test
    void createEvictsCacheAfterTransactionCommit() {
        com.chy.mall.dto.CreateProductRequest request = new com.chy.mall.dto.CreateProductRequest();
        request.setSku("NEW-SKU-001");
        request.setName("全新发布键盘");
        request.setPrice(new BigDecimal("299.00"));
        request.setInitialStock(20);

        when(productMapper.insert(any(ProductDo.class))).thenAnswer(invocation -> {
            ProductDo p = invocation.getArgument(0);
            p.setId(200L);
            return 1;
        });
        when(inventoryMapper.insert(any(ProductInventoryDo.class))).thenReturn(1);

        String cacheKey = "mall:product:detail:200";

        TransactionSynchronizationManager.initSynchronization();
        try {
            ProductVo vo = service.create(request);
            assertNotNull(vo);
            assertEquals(200L, vo.getId());

            // 尚未 commit 前不删缓存
            verify(redisTemplate, never()).delete(cacheKey);

            // commit 后触发清理
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }

            verify(redisTemplate, times(1)).delete(cacheKey);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 场景 10：防缓存击穿 —— 互斥锁与 DCL 双重检查机制
     * 预期：抢到锁后二次检查缓存，若已有数据（前序线程刚写完），立即返回，绝不重复查库，且释放锁。
     */
    @Test
    void getByIdCacheBreakdownDoubleCheckedLocking() throws Exception {
        Long productId = 888L;
        String cacheKey = "mall:product:detail:" + productId;
        String lockKey = "mall:product:lock:" + productId;

        ProductVo mockCachedVo = new ProductVo();
        mockCachedVo.setId(productId);
        mockCachedVo.setName("DCL命中商品");
        mockCachedVo.setPrice(new BigDecimal("199.00"));
        String cachedJson = objectMapper.writeValueAsString(mockCachedVo);

        // 第一次查缓存返回 null，第二次（拿到锁后的 DCL 查缓存）返回 cachedJson
        when(valueOps.get(cacheKey)).thenReturn(null, cachedJson);
        when(valueOps.setIfAbsent(eq(lockKey), eq("1"), any(Duration.class))).thenReturn(true);

        ProductVo vo = service.getById(productId);

        assertNotNull(vo);
        assertEquals("DCL命中商品", vo.getName());

        // 核心验证：DCL 生效，没有查库！
        verify(productMapper, never()).selectById(any());
        // 核心验证：执行了 finally 释放锁
        verify(redisTemplate, times(1)).delete(lockKey);
    }

    /**
     * 场景 11：防缓存击穿 —— 抢锁失败后的自旋等待与重试命中
     * 预期：第一轮抢锁失败，自旋休眠后第二轮缓存已被生成，直接命中返回，阻断并发打向 MySQL。
     */
    @Test
    void getByIdCacheBreakdownLockFailureSpinsAndHitsCache() throws Exception {
        Long productId = 777L;
        String cacheKey = "mall:product:detail:" + productId;
        String lockKey = "mall:product:lock:" + productId;

        ProductVo mockCachedVo = new ProductVo();
        mockCachedVo.setId(productId);
        mockCachedVo.setName("自旋后命中商品");
        mockCachedVo.setPrice(new BigDecimal("88.00"));
        String cachedJson = objectMapper.writeValueAsString(mockCachedVo);

        // 第 1 轮查缓存：null，抢锁：失败(false)
        // 第 2 轮查缓存：命中 cachedJson
        when(valueOps.get(cacheKey)).thenReturn(null, cachedJson);
        when(valueOps.setIfAbsent(eq(lockKey), eq("1"), any(Duration.class))).thenReturn(false);

        ProductVo vo = service.getById(productId);

        assertNotNull(vo);
        assertEquals("自旋后命中商品", vo.getName());

        // 核心验证：自旋后命中，没有查库！
        verify(productMapper, never()).selectById(any());
        // 核心验证：第一轮抢锁失败，并没有释放锁（因为锁不是自己持有的）
        verify(redisTemplate, never()).delete(lockKey);
    }

    /**
     * 场景 12：防缓存击穿 —— 互斥锁租期与释放锁的异常安全保障
     * 预期：抢锁使用的租期必须严格为 5 秒，且查库发生异常时在 finally 中依然必须释放锁防死锁。
     */
    @Test
    void getByIdCacheBreakdownLockReleaseOnException() {
        Long productId = 666L;
        String cacheKey = "mall:product:detail:" + productId;
        String lockKey = "mall:product:lock:" + productId;

        when(valueOps.get(cacheKey)).thenReturn(null);
        ArgumentCaptor<Duration> lockTtlCaptor = ArgumentCaptor.forClass(Duration.class);
        when(valueOps.setIfAbsent(eq(lockKey), eq("1"), lockTtlCaptor.capture())).thenReturn(true);

        // 模拟查库抛错
        when(productMapper.selectById(productId)).thenThrow(new RuntimeException("DB error"));

        assertThrows(RuntimeException.class, () -> service.getById(productId));

        // 核心验证 1：锁租期必须严格为 5 秒防死锁
        assertEquals(Duration.ofSeconds(5), lockTtlCaptor.getValue());
        // 核心验证 2：即使查库报错，finally 依然把锁删掉了！
        verify(redisTemplate, times(1)).delete(lockKey);
    }

    /**
     * 场景 13：高并发真实多线程击穿压测（10个并发线程同一毫秒涌入，MySQL仅被查 1 次）
     */
    @Test
    void getByIdConcurrentBreakdownSimulationQueriesDbOnlyOnce() throws Exception {
        Long productId = 555L;
        String cacheKey = "mall:product:detail:" + productId;
        String lockKey = "mall:product:lock:" + productId;

        ProductDo product = createProductDo(productId, "高并发爆款键盘", new BigDecimal("99.00"));
        ProductInventoryDo inventory = createInventoryDo(productId, 100);

        // 模拟慢 SQL 查库耗时 20ms
        when(productMapper.selectById(productId)).thenAnswer(inv -> {
            Thread.sleep(20);
            return product;
        });
        when(inventoryMapper.selectById(productId)).thenReturn(inventory);

        // 使用 Atomic 原语模拟真实的 Redis 缓存和 SETNX 互斥锁
        java.util.concurrent.atomic.AtomicReference<String> redisCache = new java.util.concurrent.atomic.AtomicReference<>(null);
        java.util.concurrent.atomic.AtomicBoolean lockHeld = new java.util.concurrent.atomic.AtomicBoolean(false);

        when(valueOps.get(cacheKey)).thenAnswer(inv -> redisCache.get());
        doAnswer(inv -> {
            redisCache.set(inv.getArgument(1));
            return null;
        }).when(valueOps).set(eq(cacheKey), anyString(), any(Duration.class));

        when(valueOps.setIfAbsent(eq(lockKey), eq("1"), any(Duration.class)))
                .thenAnswer(inv -> lockHeld.compareAndSet(false, true));

        when(redisTemplate.delete(eq(lockKey))).thenAnswer(inv -> {
            lockHeld.set(false);
            return Boolean.TRUE;
        });

        int threadCount = 10;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.List<ProductVo> results = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await(); // 10 个并发线程在此集合，同一时刻爆发
                    ProductVo vo = service.getById(productId);
                    results.add(vo);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // 发令枪响，10 个并发同时涌入！
        assertTrue(doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS));
        executor.shutdown();

        // 核心验证 1：所有 10 个线程全部正常拿到了数据
        assertEquals(10, results.size());
        for (ProductVo vo : results) {
            assertEquals("高并发爆款键盘", vo.getName());
        }

        // 核心验证 2：MySQL 数据库仅被查询了恰好 1 次！成功抵挡了 10 倍并发击穿！
        verify(productMapper, times(1)).selectById(productId);
    }

    private ProductDo createProductDo(Long id, String name, BigDecimal price) {
        ProductDo product = new ProductDo();
        product.setId(id);
        product.setSku("SKU-" + id);
        product.setName(name);
        product.setPrice(price);
        product.setStatus(ProductStatus.ON_SALE);
        return product;
    }

    private ProductInventoryDo createInventoryDo(Long productId, Integer stock) {
        ProductInventoryDo inventory = new ProductInventoryDo();
        inventory.setProductId(productId);
        inventory.setStock(stock);
        return inventory;
    }
}
