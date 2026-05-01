package com.example.hms.payload.dto.integration;

import com.example.hms.model.integration.Dhis2PeriodType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * Manual-trigger request body for {@code POST /api/admin/integrations/dhis2/exports/trigger}.
 *
 * <p>{@code periodIso} is validated against the canonical DHIS2 wire
 * format: {@code YYYYMM} (monthly), {@code YYYYW##} (weekly),
 * {@code YYYY} (yearly). The orchestrator re-checks the format against
 * the supplied {@link Dhis2PeriodType} so the regex here is a coarse
 * gate, not the source of truth.
 */
public record Dhis2TriggerRequestDTO(

    @NotNull UUID hospitalId,

    @NotBlank
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "datasetUid must be 11 chars (letter + 10 alphanumeric)")
    String datasetUid,

    @NotNull Dhis2PeriodType periodType,

    @NotBlank
    @Pattern(regexp = "^(\\d{4}(\\d{2})?|\\d{4}W\\d{1,2})$",
        message = "periodIso must match YYYY, YYYYMM, or YYYYW##")
    String periodIso
) { }
