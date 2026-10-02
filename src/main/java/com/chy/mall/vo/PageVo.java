package com.chy.mall.vo;

import lombok.Data;

import java.util.List;

// 分页返回结构，不向客户端暴露Mapper或框架内部的分页实现。
@Data
public class PageVo<T> {
    private Integer page;
    private Integer size;
    private Long total;
    // 空结果返回[]，而不是null。
    private List<T> records = List.of();
}
