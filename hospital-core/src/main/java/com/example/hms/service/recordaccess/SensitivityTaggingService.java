package com.example.hms.service.recordaccess;

import com.example.hms.enums.SensitivityCategory;
import com.example.hms.payload.dto.recordaccess.SensitivityTagResponseDTO;

import java.util.UUID;

/** E8 #51 — set the explicit tag on an encounter or a problem, or the default on a department. */
public interface SensitivityTaggingService {

    SensitivityTagResponseDTO getEncounterTag(UUID encounterId);

    /** {@code category} may be null, which clears the override. */
    SensitivityTagResponseDTO tagEncounter(UUID encounterId, SensitivityCategory category, UUID actorUserId);

    /**
     * QA D2730: tag one entry of the problem list. A null {@code category}
     * clears the problem's own tag, after which the visit it was recorded in
     * governs.
     */
    SensitivityTagResponseDTO tagProblem(UUID patientId, UUID problemId, SensitivityCategory category,
                                         UUID actorUserId);

    SensitivityTagResponseDTO getDepartmentDefault(UUID departmentId);

    SensitivityTagResponseDTO setDepartmentDefault(UUID departmentId, SensitivityCategory category, UUID actorUserId);
}
