package com.example.hms.model.converter;

import com.example.hms.model.LabTestComponent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA attribute converter that serializes panel components (r63) as JSON for storage.
 */
@Slf4j
@Converter(autoApply = false)
public class LabTestComponentConverter implements AttributeConverter<List<LabTestComponent>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<LabTestComponent> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JacksonException e) {
            log.error("Failed to serialize lab test components", e);
            throw new IllegalStateException("lab.components.unwritable", e);
        }
    }

    @Override
    public List<LabTestComponent> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, LabTestComponent.class));
        } catch (JacksonException e) {
            log.error("Failed to deserialize lab test components", e);
            return new ArrayList<>();
        }
    }
}
