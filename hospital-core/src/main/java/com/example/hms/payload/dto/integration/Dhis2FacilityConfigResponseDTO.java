package com.example.hms.payload.dto.integration;

import com.example.hms.model.integration.Dhis2AuthMode;
import com.example.hms.model.integration.Dhis2PeriodType;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response shape for a {@link com.example.hms.model.integration.Dhis2FacilityConfig}.
 *
 * <p><strong>Never carries the secret value.</strong> The
 * {@code authSecretEnvVar} field exposes the env-var <em>name</em>;
 * {@code authSecretConfigured} is a boolean operators can use to flag
 * "this env var is set on the running container".
 */
public record Dhis2FacilityConfigResponseDTO(
    UUID id,
    UUID hospitalId,
    String baseUrl,
    Dhis2AuthMode authMode,
    String authSecretEnvVar,
    boolean authSecretConfigured,
    Dhis2PeriodType defaultPeriodType,
    String defaultDatasetUid,
    LocalDateTime lastExportAt,
    boolean active,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) { }
