package io.emonlens.server.web;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 基础安全响应头（对所有响应生效）：
 * - nosniff：禁止浏览器按内容嗅探 MIME 类型（防把上传/接口 JSON 当脚本执行）；
 * - X-Frame-Options / frame-ancestors：禁止被第三方页面 iframe 嵌入（防点击劫持）；
 * - Referrer-Policy：跨站跳转不泄露完整 URL（URL 可能含 traceId 等业务信息）。
 * 未加严格 CSP（naive-ui 依赖内联样式），需要时可在反代层按部署环境追加 script-src 策略。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        filterChain.doFilter(request, response);
    }
}
