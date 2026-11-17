package com.example.hms.service;

import com.example.hms.payload.dto.medicalhistory.ImmunizationRequestDTO;
import com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ImmunizationService {

    /**
     * Create a new immunization record
     */
    ImmunizationResponseDTO createImmunization(ImmunizationRequestDTO requestDTO);

    /**
     * Get immunization by ID
     */
    ImmunizationResponseDTO getImmunizationById(UUID id);

    /**
     * Get all immunizations for a patient
     */
    List<ImmunizationResponseDTO> getImmunizationsByPatientId(UUID patientId);

    /**
     * The patient-facing read: the patient (or a proxy the portal already
     * authorised on the patient's own grant) reading their own immunizations,
     * from every hospital (design Q1). No hospital scope and no staff
     * record-access policy: ownership, established by the caller, is the
     * bound. QA D1212 (round 24).
     */
    List<ImmunizationResponseDTO> getImmunizationsForPatientPortal(UUID patientId);

    /**
     * Get immunizations by vaccine type
     */
    List<ImmunizationResponseDTO> getImmunizationsByVaccineCode(UUID patientId, String vaccineCode);

    /**
     * Get overdue immunizations
     */
    List<ImmunizationResponseDTO> getOverdueImmunizations(UUID patientId);

    /**
     * Get upcoming immunizations
     */
    List<ImmunizationResponseDTO> getUpcomingImmunizations(UUID patientId, LocalDate startDate, LocalDate endDate);

    /**
     * Get immunizations needing reminders
     */
    List<ImmunizationResponseDTO> getImmunizationsNeedingReminders(UUID patientId, LocalDate reminderDate);

    /**
     * Mark reminder as sent
     */
    void markReminderSent(UUID immunizationId);

    /**
     * Update immunization
     */
    ImmunizationResponseDTO updateImmunization(UUID id, ImmunizationRequestDTO requestDTO);

    /**
     * Delete (soft delete) immunization
     */
    void deleteImmunization(UUID id);
}
