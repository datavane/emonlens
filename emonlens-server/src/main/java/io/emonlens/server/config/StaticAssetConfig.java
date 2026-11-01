package io.emonlens.server.config;

import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 前端静态资源缓存策略：
 * /assets/** 是带 hash 的构建产物（内容不可变）→ 一年强缓存，减少重复下载。
 * SPA 入口 HTML（/、/index.html、深链）由 spring.web.resources.cache.cachecontrol.no-cache=true
 * 强制每次协商（Last-Modified/ETag），发版后拿到新 index.html，避免旧 index 引用已删除的 chunk。
 */
@Configuration
public class StaticAssetConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic());
    }
}
