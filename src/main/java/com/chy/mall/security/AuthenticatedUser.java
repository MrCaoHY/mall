package com.chy.mall.security;

import com.chy.mall.enums.UserRole;

/**
 * 验签后存入SecurityContext的当前用户身份，不包含密码或原始Token。
 */
public record AuthenticatedUser (
        Long userId,
        String username,
        UserRole role
) {
}
