package com.chy.mall.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品与库存业务。写操作使用同一事务，读操作批量关联库存，避免N+1查询。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductServiceImpl implements ProductService {
    private final ProductMapper productMapper;
    private final ProductInventoryMapper productInventoryMapper;

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
        return productVo;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductVo getById(Long productId) {
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
        return getById(productId);
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
        return getById(productId);
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
