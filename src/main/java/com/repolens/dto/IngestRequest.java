package com.repolens.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for repository ingestion endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestRequest {

    @NotBlank(message = "GitHub URL is required")
    @Pattern(
            regexp = "https?://github\\.com/[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+(?:\\.git)?/?",
            message = "Invalid GitHub repository URL format"
    )
    private String githubUrl;
}
