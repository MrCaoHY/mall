package com.chy.mall.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chy.mall.dto.CreateProductRequest;
import com.chy.mall.dto.ProductPageQuery;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.exception.BusinessException;
import com.chy.mall.exception.ErrorCode;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.service.ProductService;
import com.chy.mall.vo.PageVo;
import com.chy.mall.vo.ProductVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
            log.error("商品存在但库存记录缺失，productId={}", product.getId());
            throw new BusinessException(ErrorCode.PRODUCT_INVENTORY_MISSING);
        }

        return toProductVo(product, inventory);
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
                log.error("商品存在但库存记录缺失，productId={}", product.getId());
                throw new BusinessException(ErrorCode.PRODUCT_INVENTORY_MISSING);
            }
            return toProductVo(product, inventory);
        }).toList();
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
