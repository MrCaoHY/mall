package com.chy.mall.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chy.mall.dto.CreateProductRequest;
import com.chy.mall.dto.ProductPageQuery;
import com.chy.mall.dto.UpdateProductRequest;
import com.chy.mall.dto.UpdateProductStatusRequest;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.service.ProductService;
import com.chy.mall.util.LogSupport;
import com.chy.mall.vo.PageVo;
import com.chy.mall.vo.ProductVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 商品与库存业务。写操作使用同一事务，读操作批量关联库存，避免N+1查询。
 */
@Service
@Slf4j
public class ProductServiceImpl implements ProductService {
    private final ProductMapper productMapper;
    private final ProductInventoryMapper productInventoryMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    //缓存前缀
    private static final String PRODUCT_DETAIL_CACHE_PREFIX = "mall:product:detail:";
    //基础过期时间
    private static final Duration BASE_TTL = Duration.ofMinutes(30);

    // 防击穿互斥锁常量
    private static final String PRODUCT_LOCK_PREFIX = "mall:product:lock:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(5); // 5秒锁租期防死锁
    private static final int MAX_CACHE_REBUILD_RETRIES = 5; // 最大自旋重试次数
    private static final long RETRY_SLEEP_MILLIS = 50L; // 自旋等待间隔50ms

    // 常量定义
    private static final String EMPTY_CACHE_SENTINEL = "EMPTY";
    private static final Duration EMPTY_CACHE_TTL = Duration.ofMinutes(2); // 空值短TTL防撑爆

    // Spring 容器启动时注入所有 Bean 的全参构造器
    @Autowired
    public ProductServiceImpl(ProductMapper productMapper,
                              ProductInventoryMapper productInventoryMapper,
                              StringRedisTemplate stringRedisTemplate,
                              ObjectMapper objectMapper) {
        this.productMapper = productMapper;
        this.productInventoryMapper = productInventoryMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    // 兼容已有纯数据库单测的双参构造器（Redis 依赖置空自动降级查库）
    public ProductServiceImpl(ProductMapper productMapper, ProductInventoryMapper productInventoryMapper) {
        this(productMapper, productInventoryMapper, null, null);
    }


    //新增商品
    @Transactional(rollbackFor = Exception.class)
    @Override
    public ProductVo create(CreateProductRequest request) {

        ProductDo productDo = new ProductDo();
        productDo.setSku(request.getSku());
        productDo.setName(request.getName());
        productDo.setPrice(request.getPrice());
        productDo.setStatus(ProductStatus.ON_SALE);
        int productRows = productMapper.insert(productDo);
        if (productRows != 1) {
            throw new IllegalStateException("商品保存失败");
        }

        LogSupport.transactionOutcome(log, "product_create", productDo.getId());
        ProductInventoryDo productInventoryDo = new ProductInventoryDo();
        productInventoryDo.setProductId(productDo.getId());
        productInventoryDo.setStock(request.getInitialStock());
        int inventoryRow = productInventoryMapper.insert(productInventoryDo);
        if (inventoryRow != 1) {
            throw new IllegalStateException("库存保存失败");
        }

        ProductVo productVo = new ProductVo();
        productVo.setId(productDo.getId());
        productVo.setSku(request.getSku());
        productVo.setName(request.getName());
        productVo.setPrice(request.getPrice());
        productVo.setStatus(ProductStatus.ON_SALE);
        productVo.setStock(request.getInitialStock());
        evictCacheAfterCommit(productDo.getId());
        return productVo;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductVo getById(Long productId) {
        if (productId == null || productId <= 0) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "商品ID不合法");
        }
        // 未配置 Redis 时直接降级走数据库（兼容单测与无缓存模式）
        if (stringRedisTemplate == null) {
            return queryFromDb(productId);
        }

        String cacheKey = PRODUCT_DETAIL_CACHE_PREFIX + productId;
        String lockKey = PRODUCT_LOCK_PREFIX + productId;

        for (int i = 0; i < MAX_CACHE_REBUILD_RETRIES; i++) {
            // 1. 查缓存（若命中直接返回；若命中 EMPTY 哨兵直接抛 404）
            ProductVo cached = getFromCache(cacheKey);
            if (cached != null) {
                log.debug("event=product_cache_hit productId={}", productId);
                return cached;
            }

            // 2. 缓存未命中，竞争互斥锁（SETNX，带租期防死锁）
            if (tryLock(lockKey)) {
                try {
                    // 3. DCL 双重检查：防止排队拿锁期间，前一个持有锁的线程已经写入了缓存
                    ProductVo doubleCheck = getFromCache(cacheKey);
                    if (doubleCheck != null) {
                        log.debug("event=product_cache_hit_after_lock productId={}", productId);
                        return doubleCheck;
                    }

                    // 4. 回源查数据库
                    ProductVo productVo;
                    try {
                        productVo = queryFromDb(productId);
                    } catch (BusinessException e) {
                        if (e.getErrorCode() == ErrorCode.PRODUCT_NOT_FOUND) {
                            saveEmptyToCache(cacheKey);
                        }
                        throw e;
                    }
                    // 5. 查到真实商品回写 Redis（30分钟 + 随机抖动防雪崩）
                    saveToCache(cacheKey, productVo);
                    return productVo;
                } finally {
                    // 6. 务必在 finally 中释放锁
                    releaseLock(lockKey);
                }
            }

            // 7. 抢锁失败：休眠片刻后自旋进入下一轮重试
            try {
                Thread.sleep(RETRY_SLEEP_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "查询被中断");
            }
        }

        // 8. 超过最大重试次数兜底降级查库（防止极端并发下线程饿死）
        log.warn("event=product_cache_lock_retry_exceeded productId={}", productId);
        return queryFromDb(productId);
    }

    /**
     * 纯数据库查询并组装 ProductVo，供缓存未命中时回写，以及在写事务内回查最新提交前快照（避免读到旧缓存）。
     */
    private ProductVo queryFromDb(Long productId) {
        // 先查商品；不存在时直接结束，不继续查询库存。
        ProductDo product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        // 库存表的主键就是商品ID，因此可直接按主键查询，无需遍历或额外筛选。
        ProductInventoryDo inventory = productInventoryMapper.selectById(product.getId());
        if (inventory == null) {
            // 缺少库存记录是数据完整性问题，不能伪装成合法的库存0。
            log.error("event=inventory_record_missing productId={}", product.getId());
            throw new BusinessException(ErrorCode.PRODUCT_INVENTORY_MISSING);
        }

        log.debug("event=product_detail_read productId={}", productId);
        return toProductVo(product, inventory);
    }

    /**
     * 核心双写一致性保障：
     * 只有在当前数据库写事务真实 COMMIT 成功之后，才去删除缓存。
     * 如果没有处于事务中（如非事务调用），则直接删除。
     */
    private void evictCacheAfterCommit(Long productId) {
        if (productId == null || stringRedisTemplate == null) {
            return;
        }
        String cacheKey = PRODUCT_DETAIL_CACHE_PREFIX + productId;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteCache(cacheKey, productId);
                }
            });
        } else {
            deleteCache(cacheKey, productId);
        }
    }

    private void saveToCache(String cacheKey, ProductVo productVo) {
        if (stringRedisTemplate == null || objectMapper == null || productVo == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(productVo);
            Duration ttl = BASE_TTL.plusSeconds(ThreadLocalRandom.current().nextInt(300));
            stringRedisTemplate.opsForValue().set(cacheKey, json, ttl);
        } catch (Exception e) {
            log.warn("event=product_cache_write_failed key={}", cacheKey, LogSupport.safeStackTrace(e));        }
    }

    private void saveEmptyToCache(String cacheKey) {
        if (stringRedisTemplate == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(cacheKey, EMPTY_CACHE_SENTINEL, EMPTY_CACHE_TTL);
            log.debug("event=product_cache_empty_saved key={}", cacheKey);
        } catch (Exception e) {
            log.warn("event=product_cache_empty_save_failed key={}", cacheKey, LogSupport.safeStackTrace(e));
        }
    }

    private void deleteCache(String cacheKey, Long productId) {
        try {
            stringRedisTemplate.delete(cacheKey);
            log.info("event=product_cache_evicted productId={}", productId);
        } catch (Exception e) {
            log.error("event=product_cache_evict_failed productId={}", productId, LogSupport.safeStackTrace(e));
        }
    }

    private boolean tryLock(String lockKey) {
        if (stringRedisTemplate == null) {
            return false;
        }
        try {
            Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL);
            return Boolean.TRUE.equals(success);
        } catch (Exception e) {
            log.warn("event=product_lock_acquire_failed lockKey={}", lockKey, LogSupport.safeStackTrace(e));
            return false;
        }
    }

    private void releaseLock(String lockKey) {
        if (stringRedisTemplate == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(lockKey);
        } catch (Exception e) {
            log.warn("event=product_lock_release_failed lockKey={}", lockKey, LogSupport.safeStackTrace(e));
        }
    }

    /**
     * Redis安全操作
     */
    private ProductVo getFromCache(String cacheKey) {
        if (stringRedisTemplate == null || objectMapper == null) {
            return null; // 未配置 Redis 或单测环境，直接降级
        }
        String json = null;
        try {
            json = stringRedisTemplate.opsForValue().get(cacheKey);

        } catch (Exception e) {
            // 只有 Redis 网络报错时才降级查库
            log.warn("event=product_cache_read_fallback key={}", cacheKey, LogSupport.safeStackTrace(e));
            return null;
        }

        if (EMPTY_CACHE_SENTINEL.equals(json)) {
            log.debug("event=product_cache_empty_hit key={}", cacheKey);
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        if (json != null && !json.isBlank()) {
            try {
                return objectMapper.readValue(json, ProductVo.class);
            } catch (Exception e) {
                log.warn("event=product_cache_deserialize_failed key={}", cacheKey, LogSupport.safeStackTrace(e));
                return null;
            }
        }
        return null;
    }

    /**
     * 仅设置编辑表单允许修改的字段；SKU、状态和库存均不从该请求写入。
     * request字段格式由DTO契约保证，这里保护调用参数及实际更新结果。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductVo update(Long productId, UpdateProductRequest request) {
        if (productId == null || productId <= 0 || request == null) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "商品ID或修改请求不合法");
        }
        int affectedRows = productMapper.update(null, Wrappers.<ProductDo>lambdaUpdate()
                .eq(ProductDo::getId, productId)
                .set(ProductDo::getName, request.getName())
                .set(ProductDo::getPrice, request.getPrice()));
        if (affectedRows != 0 && affectedRows != 1) {
            throw new IllegalStateException("商品更新影响行数异常");
        }
        LogSupport.transactionOutcome(log, "product_update", productId);
        // 某些连接配置下，同值更新可能返回0；回查区分“不存在”和“值未变”。
        // 回查及库存读取在本次写事务内，组装失败时不能留下已提交的修改。
        evictCacheAfterCommit(productId);
        return queryFromDb(productId);
    }

    /**
     * 只改变是否允许新的下单意图，不修改库存，也不取消已有订单。
     * 下单读取到在售状态后已开始的事务可继续完成，沿用现有读取时裁决的约定。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductVo updateStatus(Long productId, UpdateProductStatusRequest request) {
        if (productId == null || productId <= 0 || request == null) {
            throw new BusinessException(ErrorCode.REQUEST_INVALID, "商品ID或状态请求不合法");
        }
        int affectedRows = productMapper.update(null, Wrappers.<ProductDo>lambdaUpdate()
                .eq(ProductDo::getId, productId)
                .set(ProductDo::getStatus, request.getStatus()));
        if (affectedRows != 0 && affectedRows != 1) {
            throw new IllegalStateException("商品状态更新影响行数异常");
        }
        LogSupport.transactionOutcome(log, "product_status_update", productId);
        // 同值更新可能返回0，回查可区分不存在；组装失败与状态修改一起回滚。
        evictCacheAfterCommit(productId);
        return queryFromDb(productId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageVo<ProductVo> page(ProductPageQuery query) {
        String keywordPattern = query.getKeywordPattern();
        String sku = query.getSkuFilter();
        LambdaQueryWrapper<ProductDo> wrapper = Wrappers.<ProductDo>lambdaQuery()
                // 将名称或SKU匹配放在同一个括号中，再与其他筛选条件用AND连接。
                .and(keywordPattern != null, condition -> condition
                        // 保留字面搜索%/_/!的规则，{0}由Wrapper绑定为参数。
                        .apply("name LIKE {0} ESCAPE '!'", keywordPattern)
                        .or()
                        .apply("sku LIKE {0} ESCAPE '!'", keywordPattern))
                .eq(sku != null, ProductDo::getSku, sku)
                .eq(query.getStatus() != null, ProductDo::getStatus, query.getStatus())
                .orderByDesc(ProductDo::getCreatedAt, ProductDo::getId);

        // 分页插件根据同一Wrapper查询总数并生成LIMIT/OFFSET。
        Page<ProductDo> productPage = productMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);
        PageVo<ProductVo> pageVo = new PageVo<>();
        pageVo.setPage(query.getPage());
        pageVo.setSize(query.getSize());
        pageVo.setTotal(productPage.getTotal());

        List<ProductDo> products = productPage.getRecords();
        // 无匹配或超过末页时，不查询库存，避免生成空IN。
        if (products.isEmpty()) {
            return pageVo;
        }

        // 只查询当前页的库存，用一个IN查询替代循环中的逐商品selectById。
        List<Long> productIds = products.stream().map(ProductDo::getId).toList();
        List<ProductInventoryDo> inventories = productInventoryMapper.selectList(
                Wrappers.<ProductInventoryDo>lambdaQuery()
                        .in(ProductInventoryDo::getProductId, productIds));
        Map<Long, ProductInventoryDo> inventoryByProductId = inventories.stream()
                .collect(Collectors.toMap(ProductInventoryDo::getProductId, inventory -> inventory));

        // 库存查询的返回顺序不固定：按ID关联，并保留商品分页结果的排序。
        List<ProductVo> records = products.stream().map(product -> {
            ProductInventoryDo inventory = inventoryByProductId.get(product.getId());
            if (inventory == null) {
                log.error("event=inventory_record_missing productId={}", product.getId());
                throw new BusinessException(ErrorCode.PRODUCT_INVENTORY_MISSING);
            }
            return toProductVo(product, inventory);
        }).toList();
        log.debug("event=product_page_read page={} size={} returnedCount={} total={}", query.getPage(), query.getSize(), records.size(), productPage.getTotal());
        pageVo.setRecords(records);
        return pageVo;
    }

    // 详情与分页共用映射：返回数据库实际状态、价格和当前库存。
    private ProductVo toProductVo(ProductDo product, ProductInventoryDo inventory) {
        ProductVo productVo = new ProductVo();
        productVo.setId(product.getId());
        productVo.setSku(product.getSku());
        productVo.setName(product.getName());
        productVo.setPrice(product.getPrice());
        productVo.setStatus(product.getStatus());
        productVo.setStock(inventory.getStock());
        return productVo;
    }
}
