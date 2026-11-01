package io.emonlens.server.api;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.ApiResult;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.config.EmonLensProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 简易固定窗口限流（实例内存态，Redis 分布式限流后续替换）：
 * emonlens.security.rate-limit-per-minute=0 表示不限流（dev 默认）。
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final EmonLensProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicLong windowStamp = new AtomicLong(0);
    private final AtomicLong counter = new AtomicLong(0);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int limitPerMinute = properties.getSecurity().getRateLimitPerMinute();
        if (limitPerMinute <= 0) {
            chain.doFilter(request, response);
            return;
        }
        long bucket = System.currentTimeMillis() / 60000L;
        long stamp = windowStamp.get();
        if (bucket != stamp && windowStamp.compareAndSet(stamp, bucket)) {
            counter.set(0);
        }
        if (counter.incrementAndGet() > limitPerMinute) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiResult.error(ErrorCode.RATE_LIMITED, "rate limit exceeded, retry later")));
            response.setHeader("Retry-After", "60");
            log.warn("rate limit exceeded: {}", request.getRequestURI());
            return;
        }
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 登录由 LoginGuard 做专门的防爆破限控（IP 窗口 + 账号锁定），不占全局窗口
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/") || path.startsWith("/api/v1/auth/login");
    }
}
