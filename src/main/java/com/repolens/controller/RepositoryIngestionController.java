package com.repolens.controller;

import com.repolens.dto.IngestRequest;
import com.repolens.dto.IngestResponse;
import com.repolens.service.IngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

/**
 * REST API for repository ingestion.
 * POST /api/repository/ingest - Accepts GitHub URL and triggers async ingestion.
 */
@RestController
@RequestMapping("/api/repository")
@RequiredArgsConstructor
public class RepositoryIngestionController {

    private final IngestionService ingestionService;

    @PostMapping(value = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<IngestResponse> ingest(
            @Valid @RequestBody IngestRequest request,
            @RequestParam(defaultValue = "false") boolean force) {
        return ingestionService.ingestAsync(request.getGithubUrl(), force);
    }
}
