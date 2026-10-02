package com.chy.mall.service;

import com.chy.mall.dto.CreateProductRequest;
import com.chy.mall.dto.ProductPageQuery;
import com.chy.mall.vo.PageVo;
import com.chy.mall.vo.ProductVo;

public interface ProductService {
    ProductVo create(CreateProductRequest request) ;

    // 根据商品ID查询商品基础信息及当前库存。
    ProductVo getById(Long productId);

    // 分页筛选商品，库存按当前页商品ID一次性批量查询。
    PageVo<ProductVo> page(ProductPageQuery query);
}
