package com.chy.mall.vo;

import lombok.Data;

import java.util.List;

// 分页返回结构，不向客户端暴露Mapper或框架内部的分页实现。
/**
 * 对外分页结果，隔离持久化框架类型，空列表固定返回[]。
 */
@Data
public class PageVo<T> {
    /** 当前页码，从1开始。 */
    private Integer page;
    /** 请求的每页数量。 */
    private Integer size;
    /** 满足条件的总记录数。 */
    private Long total;
    // 空结果返回[]，而不是null。
    private List<T> records = List.of();
}
