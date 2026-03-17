package com.repolens.scanner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Recursively scans a directory for .java files, maps package structure,
 * and counts total lines of code.
 */
@Component
@Slf4j
public class JavaFileScanner {

    private static final String JAVA_EXTENSION = ".java";
    /** Matches valid package declarations only (avoids false matches in comments) */
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("\\bpackage\\s+([a-zA-Z0-9_.]+)\\s*;");

    /**
     * Scans the given directory recursively for Java files.
     *
     * @param rootPath Root directory to scan (typically cloned repo)
     * @return ScanResult containing file list, package structure, and LOC count
     */
    public ScanResult scan(Path rootPath) throws IOException {
        try (Stream<Path> walk = Files.walk(rootPath)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(JAVA_EXTENSION))
                    .toList();

            Map<String, Integer> packageStructure = new ConcurrentHashMap<>();
            long totalLinesOfCode = files.parallelStream()
                    .mapToLong(file -> {
                        FileScanResult result = scanFile(file);
                        packageStructure.merge(result.packageName(), 1, Integer::sum);
                        return result.lineCount();
                    })
                    .sum();

            log.debug("Scanned {} Java files, {} total LOC", files.size(), totalLinesOfCode);
            return ScanResult.builder()
                    .javaFiles(files)
                    .packageStructure(packageStructure)
                    .totalLinesOfCode(totalLinesOfCode)
                    .build();
        }
    }

    /** Single read per file: extract package + count lines. */
    private FileScanResult scanFile(Path file) {
        try {
            String content = Files.readString(file);
            String packageName = extractPackageFromContent(content);
            long lineCount = content.lines().count();
            return new FileScanResult(packageName, lineCount);
        } catch (IOException e) {
            log.warn("Could not read file {}: {}", file, e.getMessage());
            return new FileScanResult("(unknown)", 0);
        }
    }

    private String extractPackageFromContent(String content) {
        Matcher matcher = PACKAGE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : "(default)";
    }

    private record FileScanResult(String packageName, long lineCount) {}
}
