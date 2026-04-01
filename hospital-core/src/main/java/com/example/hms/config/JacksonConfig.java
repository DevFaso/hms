package com.example.hms.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Configure Jackson to work nicely with Hibernate lazy entities
 * and to accept multiple time formats for LocalTime fields.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Module hibernate6Module() {
        Hibernate6Module module = new Hibernate6Module();
        // Do not force initialization of lazy properties during serialization
        module.disable(Hibernate6Module.Feature.FORCE_LAZY_LOADING);
        // Instead, serialize the identifier for lazy not-loaded objects
        module.enable(Hibernate6Module.Feature.SERIALIZE_IDENTIFIER_FOR_LAZY_NOT_LOADED_OBJECTS);
        return module;
    }

    /**
     * Register a flexible LocalTime deserializer that accepts both
     * 24-hour ("HH:mm", "HH:mm:ss") and 12-hour AM/PM ("h:mm a") formats.
     */
    @Bean
    public Module flexibleLocalTimeModule() {
        SimpleModule module = new SimpleModule("FlexibleLocalTime");
        module.addDeserializer(LocalTime.class, new FlexibleLocalTimeDeserializer());
        return module;
    }

    private static class FlexibleLocalTimeDeserializer extends StdDeserializer<LocalTime> {

        private static final DateTimeFormatter AMPM_FORMAT =
                DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

        FlexibleLocalTimeDeserializer() {
            super(LocalTime.class);
        }

        @Override
        public LocalTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getText().trim();
            // Try ISO formats first (HH:mm, HH:mm:ss)
            try {
                return LocalTime.parse(text);
            } catch (DateTimeParseException ignored) {
                // fall through
            }
            // Try 12-hour AM/PM format (e.g. "07:30 AM")
            try {
                return LocalTime.parse(text, AMPM_FORMAT);
            } catch (DateTimeParseException ignored) {
                // fall through
            }
            throw new IOException("Cannot parse LocalTime from: " + text
                    + ". Expected HH:mm, HH:mm:ss, or h:mm AM/PM");
        }
    }
}

