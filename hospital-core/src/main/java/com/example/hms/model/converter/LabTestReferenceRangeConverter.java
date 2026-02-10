package com.example.hms.model.converter;

import com.example.hms.model.LabTestReferenceRange;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA attribute converter that serializes reference range collections as JSON for storage.
 */
@Slf4j
@Converter(autoApply = false)
public class LabTestReferenceRangeConverter implements AttributeConverter<List<LabTestReferenceRange>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<LabTestReferenceRange> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize lab test reference ranges", e);
            throw new IllegalStateException("Unable to write reference ranges", e);
        }
    }

    @Override
    public List<LabTestReferenceRange> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, LabTestReferenceRange.class));
        } catch (IOException e) {
            log.error("Failed to deserialize lab test reference ranges", e);
            return new ArrayList<>();
        }
    }
}
