package com.repolens.controller;

import com.repolens.dto.IngestRequest;
import com.repolens.dto.IngestResponse;
import com.repolens.service.DocumentationService;
import com.repolens.service.IngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

/**
 * REST API for repository ingestion and documentation.
 * POST /api/repository/ingest - Accepts GitHub URL and triggers async ingestion.
 * GET /api/repository/{id}/docs - Fetches generated documentation.
 */
@RestController
@RequestMapping("/api/repository")
@RequiredArgsConstructor
public class RepositoryIngestionController {

    private final IngestionService ingestionService;
    private final DocumentationService documentationService;

    @PostMapping(value = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<IngestResponse> ingest(
            @Valid @RequestBody IngestRequest request,
            @RequestParam(defaultValue = "false") boolean force) {
        return ingestionService.ingestAsync(request.getGithubUrl(), force);
    }

    @GetMapping(value = "/{id}/docs", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public ResponseEntity<String> getDocumentation(@PathVariable Long id) {
        return documentationService.getDocumentation(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
