package com.repolens.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Project Map - Entry points and structure of a Spring Boot application.
 * Produced by ComponentMapper for documentation and intelligence.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMap {

    /** Root package name (e.g. com.example.app) */
    private String rootPackageName;

    /** Main modules (top-level package segments under root) */
    private List<String> mainModules;

    /** Primary controllers with their service dependencies */
    private List<ControllerEntry> controllers;

    /** All services discovered (for reference) */
    private List<ServiceEntry> services;

    /** Importance ranking: 1=Controller, 2=Service, 3=Repository, 4=DTO/Util */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ControllerEntry {
        private String name;
        private String packageName;
        private String qualifiedName;
        private int importance;  // 1 = highest (entry point)
        private List<String> serviceDependencies;  // Injected service class names
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceEntry {
        private String name;
        private String packageName;
        private String qualifiedName;
        private int importance;  // 2
    }
}
