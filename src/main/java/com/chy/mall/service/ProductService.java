package com.chy.mall.service;

import com.chy.mall.dto.CreateProductRequest;
import com.chy.mall.dto.ProductPageQuery;
import com.chy.mall.dto.UpdateProductRequest;
import com.chy.mall.dto.UpdateProductStatusRequest;
import com.chy.mall.vo.PageVo;
import com.chy.mall.vo.ProductVo;

/**
 * 商品业务契约：新增时连同库存一起保存，查询返回公开数据而非持久化实体。
 */
public interface ProductService {
    ProductVo create(CreateProductRequest request) ;

    // 根据商品ID查询商品基础信息及当前库存。
    ProductVo getById(Long productId);

    /** 修改商品名称和价格；request字段须已通过DTO校验，返回包含当前库存的商品信息。 */
    ProductVo update(Long productId, UpdateProductRequest request);

    /** 设置目标上下架状态；request字段须已通过DTO校验，同值重提也成功。 */
    ProductVo updateStatus(Long productId, UpdateProductStatusRequest request);

    // 分页筛选商品，库存按当前页商品ID一次性批量查询。
    PageVo<ProductVo> page(ProductPageQuery query);
}
