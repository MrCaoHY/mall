package com.chy.mall.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.chy.mall.dto.CreateProductRequest;
import com.chy.mall.dto.ProductPageQuery;
import com.chy.mall.entity.ProductDo;
import com.chy.mall.entity.ProductInventoryDo;
import com.chy.mall.enums.ProductStatus;
import com.chy.mall.exception.ProductInventoryMissingException;
import com.chy.mall.exception.ProductNotFoundException;
import com.chy.mall.mapper.ProductInventoryMapper;
import com.chy.mall.mapper.ProductMapper;
import com.chy.mall.service.ProductService;
import com.chy.mall.vo.PageVo;
import com.chy.mall.vo.ProductVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
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
            throw new ProductNotFoundException(productId);
        }

        // 库存表的主键就是商品ID，因此可直接按主键查询，无需遍历或额外筛选。
        ProductInventoryDo inventory = productInventoryMapper.selectById(product.getId());
        if (inventory == null) {
            // 缺少库存记录是数据完整性问题，不能伪装成合法的库存0。
            throw new ProductInventoryMissingException(product.getId());
        }

        return toProductVo(product, inventory);
    }

    @Override
    @Transactional(readOnly = true)
    public PageVo<ProductVo> page(ProductPageQuery query) {
        // 一次查询总数；在做乘法前转为long，避免大页码发生int溢出。
        long total = productMapper.countForPage(query);
        long offset = ((long) query.getPage() - 1) * query.getSize();
        PageVo<ProductVo> pageVo = new PageVo<>();
        pageVo.setPage(query.getPage());
        pageVo.setSize(query.getSize());
        pageVo.setTotal(total);

        // 无匹配或超出末页，直接返回空数组，不发出无意义的记录/库存查询。
        if (total == 0 || offset >= total) {
            return pageVo;
        }

        List<ProductDo> products = productMapper.selectForPage(query, offset);
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
                throw new ProductInventoryMissingException(product.getId());
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
