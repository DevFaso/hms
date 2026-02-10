package com.example.hms.service;

import com.example.hms.payload.dto.medication.MedicationTimelineResponseDTO;
import com.example.hms.payload.dto.medication.PharmacyFillRequestDTO;
import com.example.hms.payload.dto.medication.PharmacyFillResponseDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Service for managing patient medication history, including prescriptions and pharmacy fills.
 * Provides timeline aggregation, overlap detection, and polypharmacy assessment.
 */
public interface MedicationHistoryService {

    /**
     * Get comprehensive medication timeline for a patient.
     * Combines prescriptions and pharmacy fills with overlap and interaction detection.
     *
     * @param patientId the patient ID
     * @param hospitalId the hospital ID for context
     * @param startDate optional start date for timeline filter
     * @param endDate optional end date for timeline filter
     * @param locale the locale for messages
     * @return comprehensive timeline response with interactions and overlaps
     */
    MedicationTimelineResponseDTO getMedicationTimeline(
        UUID patientId,
        UUID hospitalId,
        LocalDate startDate,
        LocalDate endDate,
        Locale locale
    );

    /**
     * Create a pharmacy fill record (manual entry or API import).
     *
     * @param request the pharmacy fill data
     * @param locale the locale for messages
     * @return the created pharmacy fill
     */
    PharmacyFillResponseDTO createPharmacyFill(PharmacyFillRequestDTO request, Locale locale);

    /**
     * Get pharmacy fill by ID.
     *
     * @param fillId the fill ID
     * @param locale the locale for messages
     * @return the pharmacy fill
     */
    PharmacyFillResponseDTO getPharmacyFillById(UUID fillId, Locale locale);

    /**
     * Get all pharmacy fills for a patient.
     *
     * @param patientId the patient ID
     * @param hospitalId the hospital ID
     * @param locale the locale for messages
     * @return list of pharmacy fills
     */
    List<PharmacyFillResponseDTO> getPharmacyFillsByPatient(
        UUID patientId,
        UUID hospitalId,
        Locale locale
    );

    /**
     * Update pharmacy fill record.
     *
     * @param fillId the fill ID
     * @param request the updated data
     * @param locale the locale for messages
     * @return the updated pharmacy fill
     */
    PharmacyFillResponseDTO updatePharmacyFill(
        UUID fillId,
        PharmacyFillRequestDTO request,
        Locale locale
    );

    /**
     * Delete pharmacy fill record.
     *
     * @param fillId the fill ID
     * @param locale the locale for messages
     */
    void deletePharmacyFill(UUID fillId, Locale locale);
}
