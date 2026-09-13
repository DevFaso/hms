package com.example.hms.model.converter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import tools.jackson.core.JacksonException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Converter(autoApply = false)
public class DiagnosisCodesConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JacksonException e) {
            log.error("Failed to serialize diagnosis codes", e);
            throw new IllegalStateException("Unable to write diagnosis codes", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JacksonException e) {
            log.error("Failed to deserialize diagnosis codes", e);
            return new ArrayList<>();
        }
    }
}
