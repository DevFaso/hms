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
     * <p>A hospital scope is mandatory. Without one every section used to read
     * patient-wide, the registration check was skipped and the cross-hospital
     * disclosure was not recorded, so the drawer returned the patient's record
     * from every tenant unaccounted. It now refuses before reading anything.
     *
     * @param patientId the patient UUID
     * @param hospitalId the hospital the caller is acting at; {@code null} is
     *        refused
     * @return compact snapshot DTO
     * @throws com.example.hms.exception.ResourceNotFoundException (404) when
     *         {@code hospitalId} is {@code null}, or the patient does not exist
     *         — deliberately the same answer to both
     */
    PatientSnapshotDTO getSnapshot(UUID patientId, UUID hospitalId);
}
