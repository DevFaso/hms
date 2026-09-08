package com.example.hms.service.recordaccess;

import com.example.hms.enums.SensitivityCategory;
import com.example.hms.payload.dto.recordaccess.SensitivityTagResponseDTO;

import java.util.UUID;

/** E8 #51 — set the explicit tag on an encounter, or the default on a department. */
public interface SensitivityTaggingService {

    SensitivityTagResponseDTO getEncounterTag(UUID encounterId);

    /** {@code category} may be null, which clears the override. */
    SensitivityTagResponseDTO tagEncounter(UUID encounterId, SensitivityCategory category, UUID actorUserId);

    SensitivityTagResponseDTO getDepartmentDefault(UUID departmentId);

    SensitivityTagResponseDTO setDepartmentDefault(UUID departmentId, SensitivityCategory category, UUID actorUserId);
}
