package com.chy.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chy.mall.dto.ProductPageQuery;
import com.chy.mall.entity.ProductDo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProductMapper extends BaseMapper<ProductDo> {
    // 计数与记录查询复用相同筛选条件，避免total和records的筛选规则不一致。
    String PAGE_FILTERS = """
            <where>
              <if test="query.keywordPattern != null">
                AND (name LIKE #{query.keywordPattern} ESCAPE '!'
                     OR sku LIKE #{query.keywordPattern} ESCAPE '!')
              </if>
              <if test="query.skuFilter != null">
                AND sku = #{query.skuFilter}
              </if>
              <if test="query.status != null">
                AND status = #{query.status}
              </if>
            </where>
            """;

    @Select("<script>SELECT COUNT(*) FROM products " + PAGE_FILTERS + "</script>")
    long countForPage(@Param("query") ProductPageQuery query);

    // 显式LIMIT分页，不依赖尚未配置的分页拦截器；不接受客户端拼接排序SQL。
    // id作为相同创建时间下的第二排序键，让静态数据集的翻页顺序稳定。
    @Select("""
            <script>
            SELECT id, sku, name, price, status,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM products
            """ + PAGE_FILTERS + """
            ORDER BY created_at DESC, id DESC
            LIMIT #{query.size} OFFSET #{offset}
            </script>
            """)
    List<ProductDo> selectForPage(@Param("query") ProductPageQuery query, @Param("offset") long offset);
}
