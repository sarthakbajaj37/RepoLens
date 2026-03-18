package com.repolens.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.repolens.dto.ProjectMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Performs recursive dependency analysis: walks the tree from Controllers
 * through Services to Repositories, resolves entities, and detects circular
 * dependencies and deep nesting (architecture smells).
 */
@Service
@Slf4j
public class DependencyTreeService {

    private static final int MAX_DEPTH_THRESHOLD = 4;
    private static final String REPOSITORY = "Repository";
    private static final String JPA_REPOSITORY = "JpaRepository";
    private static final String CRUD_REPOSITORY = "CrudRepository";

    private final JavaParser javaParser = new JavaParser();

    /**
     * Post-processes ProjectMap with full dependency graph, circular deps, and deep nesting.
     */
    public ProjectMap analyzeDependencies(Path rootPath, ProjectMap projectMap) throws IOException {
        Map<String, ClassInfo> classIndex = buildClassIndex(rootPath);
        Map<String, List<String>> graph = new LinkedHashMap<>();
        Set<String> circularDeps = new LinkedHashSet<>();
        Set<String> deepNesting = new LinkedHashSet<>();

        for (ProjectMap.ControllerEntry controller : projectMap.getControllers()) {
            walkDependencyTree(controller.getQualifiedName(), controller.getName(), classIndex,
                    graph, new ArrayDeque<>(), circularDeps, deepNesting, 0);
        }

        List<ProjectMap.RepositoryEntry> repositories = discoverRepositories(rootPath, classIndex);

        return ProjectMap.builder()
                .rootPackageName(projectMap.getRootPackageName())
                .mainModules(projectMap.getMainModules())
                .controllers(projectMap.getControllers())
                .services(projectMap.getServices())
                .repositories(repositories)
                .dependencyGraph(graph)
                .circularDependencies(new ArrayList<>(circularDeps))
                .deepNestingWarnings(new ArrayList<>(deepNesting))
                .build();
    }

    private Map<String, ClassInfo> buildClassIndex(Path rootPath) throws IOException {
        Map<String, ClassInfo> index = new HashMap<>();
        try (Stream<Path> walk = Files.walk(rootPath)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> parseAndIndex(file, index));
        }
        return index;
    }

    private void parseAndIndex(Path file, Map<String, ClassInfo> index) {
        try {
            String content = Files.readString(file);
            ParseResult<CompilationUnit> result = javaParser.parse(content);
            result.getResult().ifPresent(cu -> {
                String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(n -> {
                    if (n.isInterface()) return;
                    String name = n.getNameAsString();
                    String qualified = pkg.isEmpty() ? name : pkg + "." + name;
                    List<String> deps = extractDependencies(n);
                    index.put(qualified, new ClassInfo(qualified, name, file, deps));
                    index.put(name, new ClassInfo(qualified, name, file, deps));
                });
            });
        } catch (IOException e) {
            log.warn("Could not index {}: {}", file, e.getMessage());
        }
    }

    private List<String> extractDependencies(ClassOrInterfaceDeclaration n) {
        Set<String> deps = new LinkedHashSet<>();
        for (FieldDeclaration field : n.getFields()) {
            if (!hasAutowiredOrInject(field)) continue;
            field.getVariables().forEach(v -> deps.add(simpleName(v.getType().asString())));
        }
        var constructors = n.getConstructors();
        for (ConstructorDeclaration ctor : constructors) {
            if (hasAutowiredOrInject(ctor) || constructors.size() == 1) {
                ctor.getParameters().forEach(p -> deps.add(simpleName(p.getType().asString())));
            }
        }
        return new ArrayList<>(deps);
    }

    private boolean hasAutowiredOrInject(FieldDeclaration f) {
        return hasAutowiredOrInject(f.getAnnotations());
    }

    private boolean hasAutowiredOrInject(ConstructorDeclaration c) {
        return hasAutowiredOrInject(c.getAnnotations());
    }

    private boolean hasAutowiredOrInject(List<AnnotationExpr> annotations) {
        return annotations.stream().anyMatch(a -> {
            String n = a.getNameAsString();
            return n.endsWith("Autowired") || n.endsWith("Inject") || n.equals("Autowired") || n.equals("Inject");
        });
    }

    private String simpleName(String type) {
        if (type.contains(".")) return type.substring(type.lastIndexOf('.') + 1);
        return type;
    }

    private void walkDependencyTree(String qualifiedName, String displayName,
                                    Map<String, ClassInfo> index,
                                    Map<String, List<String>> graph,
                                    Deque<String> path,
                                    Set<String> circularDeps,
                                    Set<String> deepNesting,
                                    int depth) {
        if (depth > MAX_DEPTH_THRESHOLD) {
            String chain = String.join(" -> ", path) + " -> " + displayName;
            deepNesting.add(chain);
            return;
        }
        if (path.contains(qualifiedName)) {
            List<String> pathList = new ArrayList<>(path);
            int start = pathList.indexOf(qualifiedName);
            if (start >= 0) {
                List<String> cycleNames = new ArrayList<>();
                for (int i = start; i < pathList.size(); i++) {
                    ClassInfo ci = index.get(pathList.get(i));
                    cycleNames.add(ci != null ? ci.simpleName : pathList.get(i));
                }
                cycleNames.add(displayName);
                circularDeps.add(String.join(" -> ", cycleNames));
            }
            return;
        }

        ClassInfo info = index.get(qualifiedName);
        if (info == null) info = index.get(displayName);
        if (info == null) return;

        List<String> children = new ArrayList<>();
        path.push(qualifiedName);

        for (String dep : info.dependencies) {
            if (isJavaOrSpringBuiltin(dep)) continue;
            ClassInfo depInfo = index.get(dep);
            if (depInfo == null) depInfo = index.get(qualifiedName + "." + dep);
            if (depInfo == null) {
                for (ClassInfo ci : index.values()) {
                    if (ci.simpleName.equals(dep)) {
                        depInfo = ci;
                        break;
                    }
                }
            }
            if (depInfo != null) {
                children.add(depInfo.qualifiedName);
                walkDependencyTree(depInfo.qualifiedName, depInfo.simpleName, index, graph, path, circularDeps, deepNesting, depth + 1);
            }
        }

        path.pop();
        graph.put(qualifiedName, children);
    }

    private boolean isJavaOrSpringBuiltin(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")
                || name.startsWith("org.springframework.") || name.startsWith("org.slf4j.")
                || name.equals("String") || name.equals("Object") || name.equals("List")
                || name.equals("Map") || name.equals("Set") || name.equals("Optional");
    }

    private List<ProjectMap.RepositoryEntry> discoverRepositories(Path rootPath, Map<String, ClassInfo> index) throws IOException {
        List<ProjectMap.RepositoryEntry> repos = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(rootPath)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> parseRepository(file, repos, index));
        }
        return repos;
    }

    private void parseRepository(Path file, List<ProjectMap.RepositoryEntry> repos, Map<String, ClassInfo> index) {
        try {
            String content = Files.readString(file);
            ParseResult<CompilationUnit> result = javaParser.parse(content);
            result.getResult().ifPresent(cu -> {
                String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(n -> {
                    if (!n.isInterface()) return;
                    if (!hasRepositoryAnnotation(n) && !extendsJpaOrCrudRepository(n)) return;
                    String name = n.getNameAsString();
                    String qualified = pkg.isEmpty() ? name : pkg + "." + name;
                    String entity = extractManagedEntity(n);
                    repos.add(ProjectMap.RepositoryEntry.builder()
                            .name(name)
                            .packageName(pkg)
                            .qualifiedName(qualified)
                            .managedEntity(entity)
                            .importance(3)
                            .build());
                });
            });
        } catch (IOException e) {
            log.warn("Could not parse repository {}: {}", file, e.getMessage());
        }
    }

    private boolean hasRepositoryAnnotation(ClassOrInterfaceDeclaration n) {
        return n.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().endsWith("Repository") || a.getNameAsString().equals("Repository"));
    }

    private boolean extendsJpaOrCrudRepository(ClassOrInterfaceDeclaration n) {
        return n.getExtendedTypes().stream()
                .anyMatch(t -> {
                    String name = t.getNameAsString();
                    return name.contains("JpaRepository") || name.contains("CrudRepository");
                });
    }

    private String extractManagedEntity(ClassOrInterfaceDeclaration n) {
        for (ClassOrInterfaceType ext : n.getExtendedTypes()) {
            if (ext.getTypeArguments().isPresent() && !ext.getTypeArguments().get().isEmpty()) {
                Type first = ext.getTypeArguments().get().get(0);
                return simpleName(first.asString());
            }
        }
        return null;
    }

    private record ClassInfo(String qualifiedName, String simpleName, Path file, List<String> dependencies) {}
}
