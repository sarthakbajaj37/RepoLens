package com.repolens.entity;

import com.repolens.dto.ProjectMap;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Map;

/**
 * Entity representing ingested repository metadata stored in MySQL.
 */
@Entity
@Table(name = "repository_metadata", indexes = {
        @Index(name = "idx_owner_name", columnList = "owner, name", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RepositoryMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String name;

    @Column(name = "clone_url", nullable = false, length = 1024)
    private String cloneUrl;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "total_lines_of_code", nullable = false)
    private long totalLinesOfCode;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    @Column(name = "package_structure", columnDefinition = "JSON")
    @Convert(converter = PackageStructureConverter.class)
    private Map<String, Integer> packageStructure;

    @Column(name = "project_map", columnDefinition = "JSON")
    @Convert(converter = ProjectMapConverter.class)
    private ProjectMap projectMap;

    @PrePersist
    protected void onCreate() {
        if (ingestedAt == null) {
            ingestedAt = Instant.now();
        }
    }
}
