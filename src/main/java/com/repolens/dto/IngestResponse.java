package com.repolens.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Response DTO for repository ingestion endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestResponse {

    private Long id;
    private String owner;
    private String name;
    private String cloneUrl;
    private int fileCount;
    private long totalLinesOfCode;
    private Instant ingestedAt;
    private Map<String, Integer> packageStructure;
}
