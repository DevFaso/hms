package com.example.hms.service;

import com.example.hms.payload.dto.clinical.PatientFlowItemDTO;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for the patient-flow kanban board.
 */
public interface PatientFlowService {

    /**
     * Return patients grouped by encounter state for the doctor's hospital.
     *
     * @param userId the doctor's user ID
     * @return map of state label → list of flow items
     */
    Map<String, List<PatientFlowItemDTO>> getPatientFlow(UUID userId);
}
