package com.repolens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.dto.GroqRequest;
import com.repolens.dto.ProjectMap;
import com.repolens.entity.RepositoryMetadata;
import com.repolens.repository.RepositoryMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generates README documentation using Groq (llama-3.3-70b) based on ProjectMap.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentationService {

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String SYSTEM_PROMPT = """
            You are a Senior Software Architect. Based on this JSON structure of a Spring Boot project, write a professional README.md.
            Include:
            1. Project Purpose - infer from the module/controller names
            2. Key Modules - list and briefly describe each main module
            3. Core Workflow - explain how controllers and services/repositories interact based on the dependencies
            4. Tech Stack details - infer from the package structure (e.g. Spring Boot, Spring Data, etc.)
            
            Output ONLY the markdown content, no code blocks or extra formatting.
            """;

    private final RepositoryMetadataRepository repository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${GROQ_API_KEY:}")
    private String groqApiKey;

    @Value("${repolens.groq.model:llama-3.3-70b-versatile}")
    private String groqModel;

    /**
     * Generates documentation for the given metadata and project map, then persists it.
     * Runs on virtual thread - non-blocking.
     */
    public void generateAndStore(RepositoryMetadata metadata) {
        if (metadata.getProjectMap() == null) {
            log.warn("No project map for {}/{} - skipping documentation", metadata.getOwner(), metadata.getName());
            return;
        }
        if (groqApiKey == null || groqApiKey.isBlank()) {
            log.warn("GROQ_API_KEY not set - skipping documentation generation. Get free key at https://console.groq.com");
            return;
        }

        try {
            String markdown = callGroq(metadata);
            if (markdown != null && !markdown.isBlank()) {
                metadata.setDocumentation(markdown);
                repository.save(metadata);
                log.info("Generated documentation for {}/{}", metadata.getOwner(), metadata.getName());
            }
        } catch (Exception e) {
            log.error("Failed to generate documentation for {}/{}: {}",
                    metadata.getOwner(), metadata.getName(), e.getMessage());
        }
    }

    private String callGroq(RepositoryMetadata metadata) throws Exception {
        String projectMapJson = objectMapper.writeValueAsString(metadata.getProjectMap());
        String userPrompt = "Project: " + metadata.getOwner() + "/" + metadata.getName() + "\n\n"
                + "Project Map JSON:\n" + projectMapJson;

        GroqRequest request = GroqRequest.builder()
                .model(groqModel)
                .messages(List.of(
                        GroqRequest.Message.builder().role("system").content(SYSTEM_PROMPT).build(),
                        GroqRequest.Message.builder().role("user").content(userPrompt).build()
                ))
                .temperature(0.4)
                .max_tokens(4096)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(groqApiKey);
        HttpEntity<GroqRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<Map> response = restTemplate.exchange(GROQ_URL, HttpMethod.POST, entity, Map.class);
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return extractTextFromGroqResponse(response.getBody());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractTextFromGroqResponse(Map<String, Object> body) {
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
            if (choices == null || choices.isEmpty()) return null;
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) return null;
            Object content = message.get("content");
            return content != null ? content.toString().trim() : null;
        } catch (Exception e) {
            log.warn("Could not parse Groq response: {}", e.getMessage());
            return null;
        }
    }

    public Optional<String> getDocumentation(Long id) {
        return repository.findById(id)
                .map(RepositoryMetadata::getDocumentation)
                .filter(doc -> doc != null && !doc.isBlank());
    }
}
