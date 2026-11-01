package io.emonlens.server.api;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.config.EmonLensProperties;

@Configuration
public class SecurityFilterConfig {

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(EmonLensProperties properties,
                                                                   ObjectMapper objectMapper) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(properties, objectMapper));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(2);
        return registration;
    }

}
