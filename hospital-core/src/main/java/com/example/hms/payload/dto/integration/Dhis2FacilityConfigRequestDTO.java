package com.example.hms.payload.dto.integration;

import com.example.hms.model.integration.Dhis2AuthMode;
import com.example.hms.model.integration.Dhis2PeriodType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record Dhis2FacilityConfigRequestDTO(

    @NotBlank(message = "{dhis2FacilityConfig.baseUrl.required}")
    @Size(max = 512)
    @Pattern(regexp = "^https?://.+", message = "{dhis2FacilityConfig.baseUrl.pattern}")
    String baseUrl,

    @NotNull(message = "{dhis2FacilityConfig.authMode.required}")
    Dhis2AuthMode authMode,

    @NotBlank(message = "{dhis2FacilityConfig.authSecretEnvVar.required}")
    @Size(max = 128)
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
        message = "{dhis2FacilityConfig.authSecretEnvVar.pattern}")
    String authSecretEnvVar,

    @NotNull(message = "{dhis2FacilityConfig.defaultPeriodType.required}")
    Dhis2PeriodType defaultPeriodType,

    @Size(max = 11)
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "{dhis2FacilityConfig.defaultDatasetUid.pattern}")
    String defaultDatasetUid,

    Boolean active
) { }
