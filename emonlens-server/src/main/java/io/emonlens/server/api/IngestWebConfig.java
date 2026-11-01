package io.emonlens.server.api;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.storage.mapper.IngestKeyMapper;

@Configuration
public class IngestWebConfig {

    @Bean
    public FilterRegistrationBean<IngestKeyFilter> ingestKeyFilter(IngestKeyMapper ingestKeyMapper,
                                                                   ObjectMapper objectMapper) {
        FilterRegistrationBean<IngestKeyFilter> registration =
                new FilterRegistrationBean<>(new IngestKeyFilter(ingestKeyMapper, objectMapper));
        registration.addUrlPatterns("/api/v1/ingest/*");
        registration.setOrder(10);
        return registration;
    }
}
