package com.example.hms.config;

import com.example.hms.enums.platform.PlatformServiceType;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Data
@ConfigurationProperties(prefix = "app.platform.integrations")
public class PlatformIntegrationProperties {

    private ModuleProperties ehr = new ModuleProperties();

    private ModuleProperties billing = new ModuleProperties();

    private ModuleProperties inventory = new ModuleProperties();

    private Map<String, ModuleProperties> modules = new HashMap<>();

    public ModuleProperties getModule(PlatformServiceType type) {
        if (type == null) {
            return new ModuleProperties();
        }

        return switch (type) {
            case EHR -> ehr;
            case BILLING -> billing;
            case INVENTORY -> inventory;
            default -> lookupAdditional(type);
        };
    }

    private ModuleProperties lookupAdditional(PlatformServiceType type) {
        String enumName = type.name();
        String lowercase = enumName.toLowerCase(Locale.ENGLISH);
        ModuleProperties exact = modules.get(enumName);
        if (exact != null) {
            return exact;
        }
        return modules.getOrDefault(lowercase, new ModuleProperties());
    }

    @Data
    public static class ModuleProperties {

        private Boolean enabled;

        private Boolean autoProvision;

        private Boolean managedByPlatform;

        private String displayName;

        private String description;

        private String provider;

        private String baseUrl;

        private String documentationUrl;

        private String sandboxUrl;

        private String onboardingGuideUrl;

        private String featureFlag;
    }
}
