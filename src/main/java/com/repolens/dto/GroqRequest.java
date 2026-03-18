package com.repolens.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * OpenAI-compatible chat completions request (used by Groq).
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroqRequest {

    private String model;
    private List<Message> messages;
    private Double temperature;
    private Integer max_tokens;

    @Data
    @Builder
    public static class Message {
        private String role;
        private String content;
    }
}
