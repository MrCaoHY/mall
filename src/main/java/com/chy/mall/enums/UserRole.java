package com.chy.mall.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户角色；默认枚举映射按名称保存为 USER 或 ADMIN。
 */
@Getter
@AllArgsConstructor
public enum UserRole {
    USER("普通用户"),
    ADMIN("管理员");

    private final String description;
}
