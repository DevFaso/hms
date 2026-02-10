package com.example.hms.config;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configure Jackson to work nicely with Hibernate lazy entities.
 *
 * We enable SERIALIZE_IDENTIFIER_FOR_LAZY_NOT_LOADED_OBJECTS so that Jackson will
 * serialize the identifier value for lazy proxies instead of trying to initialize
 * the association (which would trigger a LazyInitializationException outside of a
 * transactional session).
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
}

