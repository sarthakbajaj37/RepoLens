package com.repolens.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Project Map - Entry points and structure of a Spring Boot application.
 * Produced by ComponentMapper and DependencyTreeService for documentation and intelligence.
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

    /** All repositories with their managed entity */
    private List<RepositoryEntry> repositories;

    /** Unified dependency graph: parent -> [children]. Full recursive chain. */
    private Map<String, List<String>> dependencyGraph;

    /** Circular dependencies detected (e.g. "A -> B -> A") */
    private List<String> circularDependencies;

    /** Deep nesting warnings: chains > 4 levels (architecture smell) */
    private List<String> deepNestingWarnings;

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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RepositoryEntry {
        private String name;
        private String packageName;
        private String qualifiedName;
        private String managedEntity;  // @Entity class this repo manages
        private int importance;  // 3
    }
}
