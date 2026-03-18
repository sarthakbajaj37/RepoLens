package com.repolens.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.repolens.dto.ProjectMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Performs recursive dependency analysis using JavaParser SymbolSolver.
 * Walks the tree from Controllers through Services to Repositories,
 * resolves FQNs, and detects circular dependencies and deep nesting.
 */
@Service
@Slf4j
public class DependencyTreeService {

    private static final int MAX_DEPTH_THRESHOLD = 4;

    /**
     * Post-processes ProjectMap with full dependency graph using symbol resolution.
     */
    public ProjectMap analyzeDependencies(Path rootPath, ProjectMap projectMap) throws IOException {
        Path srcMainJava = resolveSourceRoot(rootPath);
        JavaParser parser = createParserWithSymbolSolver(srcMainJava);

        Map<String, ClassInfo> classIndex = buildClassIndex(rootPath, srcMainJava, parser);
        Map<String, List<String>> graph = new LinkedHashMap<>();
        Set<String> circularDeps = new LinkedHashSet<>();
        Set<String> deepNesting = new LinkedHashSet<>();

        for (ProjectMap.ControllerEntry controller : projectMap.getControllers()) {
            List<String> fallbackDeps = controller.getServiceDependencies() != null
                    ? controller.getServiceDependencies().stream()
                            .map(d -> resolveSimpleNameToFqn(d, controller.getPackageName(), classIndex))
                            .filter(Objects::nonNull)
                            .toList()
                    : List.of();
            walkDependencyTree(controller.getQualifiedName(), controller.getName(), classIndex,
                    graph, new ArrayDeque<>(), circularDeps, deepNesting, 0, fallbackDeps);
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

    private Path resolveSourceRoot(Path rootPath) {
        Path srcMainJava = rootPath.resolve("src/main/java");
        if (Files.exists(srcMainJava) && Files.isDirectory(srcMainJava)) {
            return srcMainJava.toAbsolutePath();
        }
        return rootPath.toAbsolutePath();
    }

    private JavaParser createParserWithSymbolSolver(Path srcMainJava) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver(false));
        typeSolver.add(new JavaParserTypeSolver(srcMainJava.toFile()));

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration config = new ParserConfiguration()
                .setSymbolResolver(symbolSolver);

        return new JavaParser(config);
    }

    private Map<String, ClassInfo> buildClassIndex(Path rootPath, Path srcMainJava, JavaParser parser) throws IOException {
        Map<String, ClassInfo> index = new HashMap<>();
        try (Stream<Path> walk = Files.walk(rootPath)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> parseAndIndex(file, index, parser));
        }
        return index;
    }

    private void parseAndIndex(Path file, Map<String, ClassInfo> index, JavaParser parser) {
        try {
            String content = Files.readString(file);
            ParseResult<CompilationUnit> result = parser.parse(content);
            result.getResult().ifPresent(cu -> {
                String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(n -> {
                    String name = n.getNameAsString();
                    String qualified = pkg.isEmpty() ? name : pkg + "." + name;
                    List<String> deps = extractDependenciesWithResolution(n, cu, pkg);
                    ClassInfo info = new ClassInfo(qualified, name, file, deps);
                    index.put(qualified, info);
                    if (!index.containsKey(name)) {
                        index.put(name, info);
                    }
                });
            });
        } catch (IOException e) {
            log.warn("Could not index {}: {}", file, e.getMessage());
        }
    }

    private static final String REQUIRED_ARGS = "RequiredArgsConstructor";
    private static final String ALL_ARGS = "AllArgsConstructor";

    private List<String> extractDependenciesWithResolution(ClassOrInterfaceDeclaration n, CompilationUnit cu, String pkg) {
        Set<String> deps = new LinkedHashSet<>();
        for (FieldDeclaration field : n.getFields()) {
            if (hasAutowiredOrInject(field)) {
                field.getVariables().forEach(v -> addResolved(deps, v.getType(), cu, pkg));
            } else if (hasLombokConstructorAnnotation(n)) {
                if (hasRequiredArgsConstructor(n) && isFinal(field)) {
                    field.getVariables().forEach(v -> addResolved(deps, v.getType(), cu, pkg));
                } else if (hasAllArgsConstructor(n)) {
                    field.getVariables().forEach(v -> addResolved(deps, v.getType(), cu, pkg));
                }
            }
        }
        var constructors = n.getConstructors();
        for (ConstructorDeclaration ctor : constructors) {
            if (hasAutowiredOrInject(ctor) || constructors.size() == 1) {
                ctor.getParameters().forEach(p -> addResolved(deps, p.getType(), cu, pkg));
            }
        }
        return new ArrayList<>(deps);
    }

    private void addResolved(Set<String> deps, Type type, CompilationUnit cu, String pkg) {
        String fqn = resolveTypeToFqn(type, cu, pkg);
        if (fqn != null) deps.add(fqn);
    }

    private boolean hasLombokConstructorAnnotation(ClassOrInterfaceDeclaration n) {
        return n.getAnnotations().stream().anyMatch(a -> {
            String name = a.getNameAsString();
            return name.endsWith(REQUIRED_ARGS) || name.equals(REQUIRED_ARGS)
                    || name.endsWith(ALL_ARGS) || name.equals(ALL_ARGS);
        });
    }

    private boolean hasRequiredArgsConstructor(ClassOrInterfaceDeclaration n) {
        return n.getAnnotations().stream().anyMatch(a -> {
            String name = a.getNameAsString();
            return name.endsWith(REQUIRED_ARGS) || name.equals(REQUIRED_ARGS);
        });
    }

    private boolean hasAllArgsConstructor(ClassOrInterfaceDeclaration n) {
        return n.getAnnotations().stream().anyMatch(a -> {
            String name = a.getNameAsString();
            return name.endsWith(ALL_ARGS) || name.equals(ALL_ARGS);
        });
    }

    private boolean isFinal(FieldDeclaration field) {
        return field.getModifiers().stream()
                .anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
    }

    private String resolveTypeToFqn(Type type, CompilationUnit cu, String pkg) {
        try {
            if (type.isClassOrInterfaceType()) {
                ClassOrInterfaceType coit = type.asClassOrInterfaceType();
                if (coit.resolve().isReferenceType()) {
                    return coit.resolve().asReferenceType().getQualifiedName();
                }
            }
        } catch (Exception e) {
            // Fallback to import-based resolution
        }
        return resolveFromImports(type.asString(), cu, pkg);
    }

    private String resolveFromImports(String typeName, CompilationUnit cu, String pkg) {
        String simpleName = typeName.contains(".") ? typeName.substring(typeName.lastIndexOf('.') + 1) : typeName;
        if (isJavaOrSpringBuiltin(simpleName)) return null;

        for (var imp : cu.getImports()) {
            if (imp.isAsterisk()) continue;
            String impStr = imp.getNameAsString();
            if (impStr.endsWith("." + simpleName) || impStr.equals(simpleName)) {
                return impStr;
            }
        }
        return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
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

    private String resolveSimpleNameToFqn(String simpleName, String packageName, Map<String, ClassInfo> index) {
        if (isJavaOrSpringBuiltin(simpleName)) return null;
        String fqn = (packageName != null && !packageName.isEmpty()) ? packageName + "." + simpleName : simpleName;
        if (index.containsKey(fqn)) return fqn;
        ClassInfo bySimple = index.get(simpleName);
        return bySimple != null ? bySimple.qualifiedName : fqn;
    }

    private void walkDependencyTree(String qualifiedName, String displayName,
                                    Map<String, ClassInfo> index,
                                    Map<String, List<String>> graph,
                                    Deque<String> path,
                                    Set<String> circularDeps,
                                    Set<String> deepNesting,
                                    int depth,
                                    List<String> fallbackDeps) {
        if (depth > MAX_DEPTH_THRESHOLD) {
            List<String> pathNames = path.stream()
                    .map(q -> index.get(q) != null ? index.get(q).simpleName : q)
                    .toList();
            deepNesting.add(String.join(" -> ", pathNames) + " -> " + displayName);
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

        List<String> depFqns = new ArrayList<>();
        if (info != null && !info.dependencies.isEmpty()) {
            depFqns.addAll(info.dependencies);
        } else if (!fallbackDeps.isEmpty()) {
            depFqns.addAll(fallbackDeps);
        }
        if (info == null && depFqns.isEmpty()) return;

        List<String> children = new ArrayList<>();
        path.push(qualifiedName);

        for (String depFqn : depFqns) {
            if (isJavaOrSpringBuiltin(depFqn)) continue;
            ClassInfo depInfo = index.get(depFqn);
            if (depInfo == null) {
                String simple = depFqn.contains(".") ? depFqn.substring(depFqn.lastIndexOf('.') + 1) : depFqn;
                depInfo = index.get(simple);
            }
            if (depInfo != null) {
                children.add(depInfo.qualifiedName);
                List<String> depFallback = List.of();
                walkDependencyTree(depInfo.qualifiedName, depInfo.simpleName, index, graph, path, circularDeps, deepNesting, depth + 1, depFallback);
            } else {
                children.add(depFqn);
            }
        }

        path.pop();
        graph.put(qualifiedName, children);
    }

    private boolean isJavaOrSpringBuiltin(String name) {
        if (name.contains(".")) {
            return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")
                    || name.startsWith("org.springframework.") || name.startsWith("org.slf4j.");
        }
        return name.equals("String") || name.equals("Object") || name.equals("List")
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
            ParseResult<CompilationUnit> result = new JavaParser().parse(content);
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
                String name = first.asString();
                return name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
            }
        }
        return null;
    }

    private record ClassInfo(String qualifiedName, String simpleName, Path file, List<String> dependencies) {}
}
