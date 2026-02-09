package com.example.hms.config;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Data
@ConfigurationProperties(prefix = "app.feature-flags")
public class FeatureFlagProperties {

    private Map<String, Boolean> defaults = new LinkedHashMap<>();

    private Map<String, Boolean> overrides = new LinkedHashMap<>();

    private Map<String, Map<String, Boolean>> environments = new LinkedHashMap<>();

    public Map<String, Boolean> getDefaultsOrEmpty() {
        return defaults != null ? defaults : Map.of();
    }

    public Map<String, Boolean> getOverridesOrEmpty() {
        return overrides != null ? overrides : Map.of();
    }

    public Map<String, Boolean> getEnvironmentOverrides(String environment) {
        if (!StringUtils.hasText(environment)) {
            return Map.of();
        }
        String key = environment.trim().toLowerCase(Locale.ENGLISH);
        Map<String, Boolean> exact = environments.get(key);
        if (exact != null) {
            return exact;
        }
        // Attempt to match without hyphen/underscore differences.
        return environments.entrySet().stream()
            .filter(entry -> normalize(entry.getKey()).equals(normalize(key)))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElse(Map.of());
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\W_]", "").toLowerCase(Locale.ENGLISH);
    }
}
