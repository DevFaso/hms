package com.example.hms.payload.dto.integration;

import com.example.hms.model.integration.Dhis2AuthMode;
import com.example.hms.model.integration.Dhis2PeriodType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record Dhis2FacilityConfigRequestDTO(

    @NotBlank(message = "DHIS2 base URL is required")
    @Size(max = 512)
    @Pattern(regexp = "^https?://.+", message = "DHIS2 base URL must be http(s)://")
    String baseUrl,

    @NotNull(message = "DHIS2 auth mode is required")
    Dhis2AuthMode authMode,

    @NotBlank(message = "Auth secret env-var name is required")
    @Size(max = 128)
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
        message = "Auth secret env-var name must be UPPER_SNAKE_CASE")
    String authSecretEnvVar,

    @NotNull(message = "Default period type is required")
    Dhis2PeriodType defaultPeriodType,

    @Size(max = 11)
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "DHIS2 dataset UID must be 11 chars (letter + 10 alphanumeric)")
    String defaultDatasetUid,

    Boolean active
) { }
