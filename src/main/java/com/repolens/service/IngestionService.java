package com.repolens.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.repolens.dto.IngestResponse;
import com.repolens.dto.ProjectMap;
import com.repolens.entity.RepositoryMetadata;
import com.repolens.mapper.ComponentMapper;
import com.repolens.repository.RepositoryMetadataRepository;
import com.repolens.service.DependencyTreeService;
import com.repolens.service.DocumentationService;
import com.repolens.scanner.JavaFileScanner;
import com.repolens.scanner.ScanResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Ingestion Engine: Clones GitHub repos via JGit, scans for Java files,
 * and persists metadata. Uses Virtual Threads for non-blocking, scalable cloning.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IngestionService {

    private static final Pattern GITHUB_URL_PATTERN = Pattern.compile(
            "https?://github\\.com/([a-zA-Z0-9_.-]+)/([a-zA-Z0-9_.-]+?)(?:\\.git)?/?$"
    );

    private final RepositoryMetadataRepository repository;
    private final JavaFileScanner fileScanner;
    private final ComponentMapper componentMapper;
    private final DependencyTreeService dependencyTreeService;
    private final DocumentationService documentationService;
    private final ExecutorService virtualThreadExecutor;

    @Value("${repolens.clone.temp-dir:/tmp/repolens-clones}")
    private String tempDir;

    /**
     * Ingests a GitHub repository asynchronously using Virtual Threads.
     * Cloning runs on a virtual thread to avoid blocking the main application.
     *
     * @param githubUrl Valid GitHub repository URL
     * @return CompletableFuture with IngestResponse
     */
    public CompletableFuture<IngestResponse> ingestAsync(String githubUrl, boolean force) {
        return CompletableFuture.supplyAsync(() -> ingest(githubUrl, force), virtualThreadExecutor);
    }

    /**
     * Synchronous ingestion - useful when called from within a virtual thread.
     */
    public IngestResponse ingest(String githubUrl, boolean force) {
        Matcher matcher = GITHUB_URL_PATTERN.matcher(githubUrl.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid GitHub URL: " + githubUrl);
        }

        String owner = matcher.group(1);
        String name = matcher.group(2);
        String cloneUrl = githubUrl.endsWith(".git") ? githubUrl : githubUrl + ".git";

        // Return cached response if already ingested (skip clone), unless force=true
        if (!force) {
            return repository.findByOwnerAndName(owner, name)
                    .map(this::toIngestResponse)
                    .orElseGet(() -> cloneAndIngest(owner, name, cloneUrl));
        }
        return cloneAndIngest(owner, name, cloneUrl);
    }

    private IngestResponse cloneAndIngest(String owner, String name, String cloneUrl) {
        Path clonePath = null;
        try {
            clonePath = createCloneDirectory(owner, name);
            log.info("Cloning {} into {}", cloneUrl, clonePath);

            Git.cloneRepository()
                    .setURI(cloneUrl)
                    .setDirectory(clonePath.toFile())
                    .setCloneAllBranches(false)
                    .setDepth(1)  // Shallow clone: only latest commit - major speedup
                    .call()
                    .close();

            ScanResult scanResult = fileScanner.scan(clonePath);
            ProjectMap projectMap = dependencyTreeService.analyzeDependencies(clonePath, componentMapper.mapProject(clonePath));
            ProjectMap finalProjectMap = projectMap;

            RepositoryMetadata metadata = repository.findByOwnerAndName(owner, name)
                    .map(existing -> {
                        existing.setCloneUrl(cloneUrl);
                        existing.setFileCount(scanResult.getJavaFiles().size());
                        existing.setTotalLinesOfCode(scanResult.getTotalLinesOfCode());
                        existing.setPackageStructure(scanResult.getPackageStructure());
                        existing.setProjectMap(finalProjectMap);
                        existing.setIngestedAt(Instant.now());
                        return existing;
                    })
                    .orElseGet(() -> RepositoryMetadata.builder()
                            .owner(owner)
                            .name(name)
                            .cloneUrl(cloneUrl)
                            .fileCount(scanResult.getJavaFiles().size())
                            .totalLinesOfCode(scanResult.getTotalLinesOfCode())
                            .packageStructure(scanResult.getPackageStructure())
                            .projectMap(finalProjectMap)
                            .build());

            metadata = repository.save(metadata);
            log.info("Ingested repository {}/{}: {} files, {} LOC",
                    owner, name, metadata.getFileCount(), metadata.getTotalLinesOfCode());

            // Generate documentation asynchronously (virtual thread - non-blocking)
            RepositoryMetadata finalMetadata = metadata;
            CompletableFuture.runAsync(
                    () -> documentationService.generateAndStore(finalMetadata),
                    virtualThreadExecutor
            );

            return toIngestResponse(metadata);

        } catch (GitAPIException e) {
            throw new com.repolens.exception.GitOperationException("Failed to clone repository: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new com.repolens.exception.GitOperationException("Failed to scan repository: " + e.getMessage(), e);
        } finally {
            if (clonePath != null) {
                deleteRecursively(clonePath);
            }
        }
    }

    private IngestResponse toIngestResponse(RepositoryMetadata metadata) {
        Map<String, Integer> pkg = metadata.getPackageStructure();
        return IngestResponse.builder()
                .id(metadata.getId())
                .owner(metadata.getOwner())
                .name(metadata.getName())
                .cloneUrl(metadata.getCloneUrl())
                .fileCount(metadata.getFileCount())
                .totalLinesOfCode(metadata.getTotalLinesOfCode())
                .ingestedAt(metadata.getIngestedAt())
                .packageStructure(pkg != null ? pkg : Collections.emptyMap())
                .projectMap(metadata.getProjectMap())
                .build();
    }

    private Path createCloneDirectory(String owner, String name) throws IOException {
        Path base = Path.of(tempDir);
        Files.createDirectories(base);
        return Files.createTempDirectory(base, owner + "-" + name + "-");
    }

    private void deleteRecursively(Path path) {
        try {
            if (Files.exists(path)) {
                try (var stream = Files.walk(path)) {
                    stream.sorted((a, b) -> -a.compareTo(b))
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException e) {
                                    log.warn("Could not delete {}: {}", p, e.getMessage());
                                }
                            });
                }
            }
        } catch (IOException e) {
            log.warn("Could not clean up clone directory {}: {}", path, e.getMessage());
        }
    }
}
