package com.example.hms.payload.dto.platform;

import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.AdmissionType;
import com.example.hms.enums.EncounterType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Admin-API write payload for {@code platform.adt_intake_provider_configs}
 * (roadmap row 24 admin-UI follow-on). One row per hospital — the
 * service layer enforces the unique-by-hospital invariant on top of
 * the schema constraint from V103.
 */
public record AdtIntakeProviderConfigRequestDTO(
    @NotNull(message = "{adtIntakeProviderConfig.hospitalId.required}")
    UUID hospitalId,

    @NotNull(message = "{adtIntakeProviderConfig.admittingProviderId.required}")
    UUID admittingProviderId,

    UUID departmentId,

    UUID defaultAssignmentId,

    @NotNull(message = "{adtIntakeProviderConfig.defaultAdmissionType.required}")
    AdmissionType defaultAdmissionType,

    @NotNull(message = "{adtIntakeProviderConfig.defaultAcuityLevel.required}")
    AcuityLevel defaultAcuityLevel,

    @NotNull(message = "{adtIntakeProviderConfig.defaultEncounterType.required}")
    EncounterType defaultEncounterType,

    @Size(max = 500, message = "{adtIntakeProviderConfig.defaultChiefComplaint.size}")
    String defaultChiefComplaint,

    Boolean enabled
) {
    @JsonCreator
    public AdtIntakeProviderConfigRequestDTO(
        @JsonProperty("hospitalId") UUID hospitalId,
        @JsonProperty("admittingProviderId") UUID admittingProviderId,
        @JsonProperty("departmentId") UUID departmentId,
        @JsonProperty("defaultAssignmentId") UUID defaultAssignmentId,
        @JsonProperty("defaultAdmissionType") AdmissionType defaultAdmissionType,
        @JsonProperty("defaultAcuityLevel") AcuityLevel defaultAcuityLevel,
        @JsonProperty("defaultEncounterType") EncounterType defaultEncounterType,
        @JsonProperty("defaultChiefComplaint") String defaultChiefComplaint,
        @JsonProperty("enabled") Boolean enabled
    ) {
        this.hospitalId = hospitalId;
        this.admittingProviderId = admittingProviderId;
        this.departmentId = departmentId;
        this.defaultAssignmentId = defaultAssignmentId;
        this.defaultAdmissionType = defaultAdmissionType;
        this.defaultAcuityLevel = defaultAcuityLevel;
        this.defaultEncounterType = defaultEncounterType;
        this.defaultChiefComplaint = defaultChiefComplaint;
        this.enabled = enabled;
    }
}
