package com.example.hms.model.converter;

import com.example.hms.model.medication.PediatricWeightBand;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Round 18 (QA D935): a catalogue item's paediatric weight bands, stored as JSON. */
@Slf4j
@Converter(autoApply = false)
public class PediatricWeightBandConverter implements AttributeConverter<List<PediatricWeightBand>, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<PediatricWeightBand> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JacksonException e) {
            throw new IllegalStateException("medicationCatalogItem.pediatricWeightBands.unwritable", e);
        }
    }

    @Override
    public List<PediatricWeightBand> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return OBJECT_MAPPER.readValue(dbData,
                OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, PediatricWeightBand.class));
        } catch (JacksonException e) {
            log.error("Failed to read paediatric weight bands; the item is treated as having none", e);
            return new ArrayList<>();
        }
    }
}
