package com.repolens.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.dto.ProjectMap;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for ProjectMap to/from JSON.
 */
@Converter
public class ProjectMapConverter implements AttributeConverter<ProjectMap, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(ProjectMap attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize project map", e);
        }
    }

    @Override
    public ProjectMap convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, ProjectMap.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot deserialize project map", e);
        }
    }
}
