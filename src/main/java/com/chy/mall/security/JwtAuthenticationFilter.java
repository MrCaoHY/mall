package com.chy.mall.security;

import com.chy.mall.enums.UserRole;
import com.chy.mall.util.JwtUtil;
import com.chy.mall.util.LogSupport;
import org.slf4j.MDC;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;


/**
 * 解析Bearer Token并建立无状态认证；无效凭证清理上下文，由安全链决定是否返回401。
 */
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = authorization.substring(7);
        if (!StringUtils.hasText(token)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            Claims claims = jwtUtil.parseToken(token);
            Long userId = jwtUtil.getUserId(claims);
            String username = jwtUtil.getUsername(claims);
            UserRole role = jwtUtil.getRole(claims);
            if (userId == null || userId <= 0 || !StringUtils.hasText(username)) {
                throw new IllegalArgumentException("认证身份无效");
            }
            AuthenticatedUser principal = new AuthenticatedUser(userId, username, role);
            SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + role.name());
            UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(authority));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            // 身份来自验签后的Claims，不采信客户端单独传入的用户ID。
            request.setAttribute(LogSupport.USER_ID_ATTRIBUTE, userId);
            MDC.put(LogSupport.USER_ID, userId.toString());
        } catch (JwtException | IllegalArgumentException exception) {
            log.warn("event=jwt_rejected reason=invalid_or_expired");
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }
}
