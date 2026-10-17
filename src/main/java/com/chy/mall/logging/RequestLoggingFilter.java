package com.chy.mall.logging;

import com.chy.mall.util.LogSupport;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 在Spring Security之前包住请求，401/403也能获得请求编号与访问日志。
 * 当前接口均为同步Servlet请求；此编号用于单次请求关联，不冒充跨服务的traceId。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        // 由服务端生成，不信任客户端提供的编号，避免伪造关联关系和日志注入。
        MDC.clear();
        String requestId = UUID.randomUUID().toString();
        MDC.put(LogSupport.REQUEST_ID, requestId);
        response.setHeader(LogSupport.REQUEST_ID_HEADER, requestId);
        long started = System.nanoTime();
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            failed = true;
            // MVC已处理的异常不会进入这里；这里只记录过滤器链逃逸的异常。
            log.error("event=http_unhandled_failure exceptionType={}", exception.getClass().getName(), LogSupport.safeStackTrace(exception));
            throw exception;
        } finally {
            try {
                Object userId = request.getAttribute(LogSupport.USER_ID_ATTRIBUTE);
                if (userId instanceof Long id) {
                    MDC.put(LogSupport.USER_ID, id.toString());
                }
                int status = failed && response.getStatus() < 400 ? 500 : response.getStatus();
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
                log.info("event=http_access method={} route={} status={} durationMs={} outcome={}",
                        method, route(request), status, durationMs, failed ? "failed" : "completed");
            } finally {
                // Tomcat复用工作线程，必须在成功、拒绝和异常路径都清理MDC。
                LogSupport.restoreContext(previous);
            }
        }
    }

    /** 只记录路由模板，避免路径、查询参数、用户名或任意攻击文本进入日志。 */
    private String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String route) {
            return route;
        }
        // 被Security提前拒绝时还没有匹配MVC路由，使用固定业务分组。
        String path = request.getServletPath();
        if (path.startsWith("/orders/")) {
            return "/orders/**";
        }
        if (path.startsWith("/products/")) {
            return "/products/**";
        }
        if (path.startsWith("/users/")) {
            return "/users/**";
        }
        return "UNMATCHED";
    }
}
