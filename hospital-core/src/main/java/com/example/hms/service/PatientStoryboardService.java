package com.example.hms.service;

import com.example.hms.payload.dto.storyboard.PatientStoryboardDTO;

import java.util.UUID;

/**
 * Aggregates the data shown in the persistent Storyboard patient banner
 * (allergies / problems / active encounter / code status). The service is
 * read-only; all source data lives in the existing clinical tables.
 */
public interface PatientStoryboardService {

    /**
     * Build the storyboard summary for a patient, optionally scoped to a hospital.
     *
     * @param patientId  patient whose chart is being viewed
     * @param hospitalId hospital scope (may be {@code null} to read across the patient's
     *                   registered hospitals — mirrors the existing patient APIs)
     * @return populated DTO; never {@code null}
     */
    PatientStoryboardDTO getStoryboard(UUID patientId, UUID hospitalId);
}
