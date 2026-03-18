package com.repolens.mapper;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.repolens.dto.ProjectMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Maps Spring Boot application structure: Controllers, Services, and their relationships.
 * Uses JavaParser for static analysis without executing the code.
 */
@Component
@Slf4j
public class ComponentMapper {

    private static final String REST_CONTROLLER = "RestController";
    private static final String CONTROLLER = "Controller";
    private static final String SERVICE = "Service";
    private static final String AUTOWIRED = "Autowired";
    private static final String QUALIFIER = "Qualifier";

    private static final int IMPORTANCE_CONTROLLER = 1;
    private static final int IMPORTANCE_SERVICE = 2;

    private final JavaParser javaParser = new JavaParser();

    /**
     * Analyzes the cloned repository and produces a Project Map.
     */
    public ProjectMap mapProject(Path rootPath) throws IOException {
        List<ProjectMap.ControllerEntry> controllers = new ArrayList<>();
        List<ProjectMap.ServiceEntry> services = new ArrayList<>();
        Set<String> packages = new HashSet<>();

        try (Stream<Path> walk = Files.walk(rootPath)) {
            List<Path> javaFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();

            for (Path file : javaFiles) {
                try {
                    String content = Files.readString(file);
                    ParseResult<CompilationUnit> result = javaParser.parse(content);
                    result.getResult().ifPresent(cu -> {
                        String pkg = cu.getPackageDeclaration()
                                .map(pd -> pd.getNameAsString())
                                .orElse("(default)");
                        packages.add(pkg);

                        cu.accept(new VoidVisitorAdapter<Void>() {
                            @Override
                            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                                if (n.isInterface()) return;

                                if (hasAnnotation(n, REST_CONTROLLER) || hasAnnotation(n, CONTROLLER)) {
                                    String className = n.getNameAsString();
                                    String qualifiedName = pkg.isEmpty() ? className : pkg + "." + className;
                                    List<String> deps = extractInjectedServices(n);
                                    controllers.add(ProjectMap.ControllerEntry.builder()
                                            .name(className)
                                            .packageName(pkg)
                                            .qualifiedName(qualifiedName)
                                            .importance(IMPORTANCE_CONTROLLER)
                                            .serviceDependencies(deps)
                                            .build());
                                } else if (hasAnnotation(n, SERVICE)) {
                                    String className = n.getNameAsString();
                                    String qualifiedName = pkg.isEmpty() ? className : pkg + "." + className;
                                    services.add(ProjectMap.ServiceEntry.builder()
                                            .name(className)
                                            .packageName(pkg)
                                            .qualifiedName(qualifiedName)
                                            .importance(IMPORTANCE_SERVICE)
                                            .build());
                                }
                                super.visit(n, arg);
                            }
                        }, null);
                    });
                } catch (IOException e) {
                    log.warn("Could not read {}: {}", file, e.getMessage());
                }
            }
        }

        String rootPackage = deriveRootPackage(packages);
        List<String> mainModules = deriveMainModules(packages, rootPackage);

        return ProjectMap.builder()
                .rootPackageName(rootPackage)
                .mainModules(mainModules)
                .controllers(controllers)
                .services(services)
                .repositories(Collections.emptyList())
                .dependencyGraph(Collections.emptyMap())
                .circularDependencies(Collections.emptyList())
                .deepNestingWarnings(Collections.emptyList())
                .build();
    }

    private boolean hasAnnotation(ClassOrInterfaceDeclaration n, String annotationName) {
        return n.getAnnotations().stream()
                .anyMatch(a -> {
                    String name = a.getNameAsString();
                    return name.endsWith("." + annotationName) || name.equals(annotationName);
                });
    }

    private List<String> extractInjectedServices(ClassOrInterfaceDeclaration n) {
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

    private String simpleName(String typeName) {
        if (typeName.contains(".")) {
            return typeName.substring(typeName.lastIndexOf('.') + 1);
        }
        return typeName;
    }

    private boolean hasAutowiredOrInject(FieldDeclaration field) {
        return hasAutowiredOrInjectAnnotation(field.getAnnotations());
    }

    private boolean hasAutowiredOrInject(ConstructorDeclaration ctor) {
        return hasAutowiredOrInjectAnnotation(ctor.getAnnotations());
    }

    private boolean hasAutowiredOrInjectAnnotation(List<AnnotationExpr> annotations) {
        return annotations.stream()
                .anyMatch(a -> {
                    String name = a.getNameAsString();
                    return name.endsWith("Autowired") || name.endsWith("Inject") || name.equals("Autowired") || name.equals("Inject");
                });
    }

    private String deriveRootPackage(Set<String> packages) {
        if (packages.isEmpty()) return "";
        List<String> sorted = new ArrayList<>(packages);
        sorted.sort(Comparator.comparingInt(String::length));
        String common = sorted.get(0);
        for (String pkg : sorted) {
            while (!pkg.startsWith(common)) {
                int lastDot = common.lastIndexOf('.');
                if (lastDot <= 0) return "default";
                common = common.substring(0, lastDot);
            }
        }
        return common.isEmpty() ? "default" : common;
    }

    private List<String> deriveMainModules(Set<String> packages, String rootPackage) {
        Set<String> modules = new TreeSet<>();
        for (String pkg : packages) {
            if (pkg.startsWith(rootPackage + ".")) {
                String remainder = pkg.substring(rootPackage.length() + 1);
                int dot = remainder.indexOf('.');
                String module = dot > 0 ? remainder.substring(0, dot) : remainder;
                if (!module.isEmpty()) modules.add(module);
            }
        }
        return new ArrayList<>(modules);
    }
}
