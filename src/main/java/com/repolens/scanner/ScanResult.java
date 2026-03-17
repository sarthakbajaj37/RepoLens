package com.repolens.scanner;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Result of scanning a repository for Java files.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanResult {

    private List<Path> javaFiles;
    private Map<String, Integer> packageStructure;  // package name -> file count
    private long totalLinesOfCode;
}
