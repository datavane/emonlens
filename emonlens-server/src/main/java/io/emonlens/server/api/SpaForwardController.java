package io.emonlens.server.api;

import javax.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 深链接回退：页面路径转发到前端 index.html（由 vue-router 接管）。
 *
 * 逐条列页面路径会变成维护陷阱——前端 router 自带 {@code :pathMatch(.*)*} 兜底重定向到
 * /dashboard，拼错或过期的链接本意就是交给 UI 处理，但服务端只认白名单，深链接（刷新、
 * 书签、手输）会落到 Spring 的 Whitelabel 404 页，兜底形同虚设。因此额外接一个单段兜底。
 *
 * 兜底用 {@code {seg:[^.]+}} 而不用 {@code /**}：RequestMappingHandlerMapping 的优先级
 * 高于静态资源 handler，{@code /**} 会把 /assets/*.js 一起劫走变成 404，前端直接白屏；
 * 排除带点的段位（.js/.css/.png/…）后静态资源仍走资源处理器，favicon 也不受影响。
 */
@Controller
public class SpaForwardController {

    @GetMapping({
            "/", "/login", "/dashboard", "/traces/**", "/traces/compare", "/alerts", "/usage", "/users",
            "/evaluators", "/evaluations", "/sessions", "/sessions/**", "/agents", "/playground", "/prompts",
            "/annotation-queues", "/annotation-queues/**", "/datasets", "/datasets/**", "/models",
            "/trust/**", "/sys/**",
            // 兜底：单段页面路径（含拼错/过期的一级链接）交给前端，别把用户留在 Whitelabel 404 上
            "/{seg:[^.]+}"
    })
    public String forward(HttpServletRequest request) {
        return "forward:/index.html";
    }
}
