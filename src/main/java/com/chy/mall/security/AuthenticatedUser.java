package com.chy.mall.security;

import com.chy.mall.enums.UserRole;

public record AuthenticatedUser (
        Long userId,
        String username,
        UserRole role
) {
}
