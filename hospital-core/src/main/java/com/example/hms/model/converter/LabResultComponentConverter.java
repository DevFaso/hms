package com.example.hms.model.converter;

import com.example.hms.model.LabResultComponent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA attribute converter that serializes panel result values (r63) as JSON for storage.
 */
@Slf4j
@Converter(autoApply = false)
public class LabResultComponentConverter implements AttributeConverter<List<LabResultComponent>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<LabResultComponent> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JacksonException e) {
            log.error("Failed to serialize lab result components", e);
            throw new IllegalStateException("lab.components.unwritable", e);
        }
    }

    @Override
    public List<LabResultComponent> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, LabResultComponent.class));
        } catch (JacksonException e) {
            log.error("Failed to deserialize lab result components", e);
            return new ArrayList<>();
        }
    }
}
