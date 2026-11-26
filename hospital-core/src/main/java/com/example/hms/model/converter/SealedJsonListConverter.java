package com.example.hms.model.converter;

import com.example.hms.security.EncryptedStringConverter;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * A JSON list kept encrypted at rest inside a {@code jsonb} column: the list is written as JSON, encrypted with
 * the PHI key ({@link EncryptedStringConverter}) and stored as the one-field document
 * {@code {"sealed": "gcm1:…"}}. The column stays {@code jsonb}, so no column type changes.
 *
 * <p>A value written before this converter is a plain JSON array and is read as it is. A value that cannot be
 * read comes back as {@code null} (the caller falls back to the stored text) and is logged without its content.
 */
@Slf4j
@Converter(autoApply = false)
public class SealedJsonListConverter implements AttributeConverter<List<Object>, String> {

    static final String SEALED = "sealed";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EncryptedStringConverter cipher = new EncryptedStringConverter();

    @Override
    public String convertToDatabaseColumn(List<Object> attribute) {
        if (attribute == null) {
            return null;
        }
        return JSON.writeValueAsString(Map.of(SEALED, cipher.convertToDatabaseColumn(JSON.writeValueAsString(attribute))));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(dbData);
            if (node.isArray()) {
                return JSON.treeToValue(node, List.class);
            }
            JsonNode sealed = node.get(SEALED);
            if (sealed == null || !sealed.isString()) {
                log.error("A sealed JSON list has an unknown shape; read as absent");
                return null;
            }
            return JSON.readValue(cipher.convertToEntityAttribute(sealed.asString()), List.class);
        } catch (JacksonException ex) {
            log.error("A sealed JSON list could not be read ({}); read as absent", ex.getClass().getSimpleName());
            return null;
        }
    }
}
