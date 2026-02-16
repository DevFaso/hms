package com.example.hms.service;

import com.example.hms.payload.dto.discharge.DischargeSummaryRequestDTO;
import com.example.hms.payload.dto.discharge.DischargeSummaryResponseDTO;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Service interface for DischargeSummary operations
 * Part of Story #14: Discharge Summary Assembly
 */
public interface DischargeSummaryService {

    /**
     * Create a new discharge summary
     */
    @Transactional
    DischargeSummaryResponseDTO createDischargeSummary(DischargeSummaryRequestDTO request, Locale locale);

    /**
     * Update an existing discharge summary
     */
    @Transactional
    DischargeSummaryResponseDTO updateDischargeSummary(UUID summaryId, DischargeSummaryRequestDTO request, Locale locale);

    /**
     * Finalize a discharge summary (lock it)
     */
    @Transactional
    DischargeSummaryResponseDTO finalizeDischargeSummary(UUID summaryId, String providerSignature, UUID providerId, Locale locale);

    /**
     * Get discharge summary by ID
     */
    @Transactional(readOnly = true)
    DischargeSummaryResponseDTO getDischargeSummaryById(UUID summaryId, Locale locale);

    /**
     * Get discharge summary by encounter ID
     */
    @Transactional(readOnly = true)
    DischargeSummaryResponseDTO getDischargeSummaryByEncounter(UUID encounterId, Locale locale);

    /**
     * Get all discharge summaries for a patient
     */
    @Transactional(readOnly = true)
    List<DischargeSummaryResponseDTO> getDischargeSummariesByPatient(UUID patientId, Locale locale);

    /**
     * Get discharge summaries for a hospital within a date range
     */
    @Transactional(readOnly = true)
    List<DischargeSummaryResponseDTO> getDischargeSummariesByHospitalAndDateRange(
        UUID hospitalId,
        LocalDate startDate,
        LocalDate endDate,
        Locale locale
    );

    /**
     * Get unfinalized discharge summaries for a hospital
     */
    @Transactional(readOnly = true)
    List<DischargeSummaryResponseDTO> getUnfinalizedDischargeSummaries(UUID hospitalId, Locale locale);

    /**
     * Get discharge summaries with pending test results for a hospital
     */
    @Transactional(readOnly = true)
    List<DischargeSummaryResponseDTO> getDischargeSummariesWithPendingResults(UUID hospitalId, Locale locale);

    /**
     * Get discharge summaries by provider
     */
    @Transactional(readOnly = true)
    List<DischargeSummaryResponseDTO> getDischargeSummariesByProvider(UUID providerId, Locale locale);

    /**
     * Delete a discharge summary (only if not finalized)
     */
    @Transactional
    void deleteDischargeSummary(UUID summaryId, UUID deletedByProviderId);
}
