package com.repolens.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async configuration using Virtual Threads (Project Loom).
 * Spring Boot 4.0 functional-style: explicit bean registration.
 */
@Configuration
public class AsyncConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
