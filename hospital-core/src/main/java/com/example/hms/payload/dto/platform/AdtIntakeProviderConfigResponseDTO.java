package com.example.hms.payload.dto.platform;

import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.AdmissionType;
import com.example.hms.enums.EncounterType;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Admin-API read payload for {@code platform.adt_intake_provider_configs}
 * (roadmap row 24 admin-UI follow-on). {@code hospitalName} is
 * projected from the JOIN for display purposes and is nullable when
 * the hospital row has no name set (data-rebuild window).
 */
public record AdtIntakeProviderConfigResponseDTO(
    UUID id,
    UUID hospitalId,
    String hospitalName,
    UUID admittingProviderId,
    UUID departmentId,
    UUID defaultAssignmentId,
    AdmissionType defaultAdmissionType,
    AcuityLevel defaultAcuityLevel,
    EncounterType defaultEncounterType,
    String defaultChiefComplaint,
    boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
