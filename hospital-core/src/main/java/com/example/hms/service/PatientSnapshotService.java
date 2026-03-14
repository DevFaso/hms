package com.example.hms.service;

import com.example.hms.payload.dto.clinical.PatientSnapshotDTO;

import java.util.UUID;

/**
 * Service for the compact patient snapshot (slide-out drawer).
 */
public interface PatientSnapshotService {

    /**
     * Build a compact patient summary aggregating demographics, allergies,
     * active medications, recent vitals, latest labs, pending orders and care team.
     *
     * @param patientId the patient UUID
     * @return compact snapshot DTO
     */
    PatientSnapshotDTO getSnapshot(UUID patientId);
}
