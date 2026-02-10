package com.example.hms.service;

import com.example.hms.payload.dto.clinical.MaternalHistoryRequestDTO;
import com.example.hms.payload.dto.clinical.MaternalHistoryResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Service interface for Maternal History operations.
 * Handles comprehensive maternal and reproductive health documentation with version tracking.
 */
public interface MaternalHistoryService {

    /**
     * Create a new maternal history record.
     *
     * @param request  The maternal history data
     * @param username The username of the user creating the record
     * @return The created maternal history response
     */
    MaternalHistoryResponseDTO createMaternalHistory(MaternalHistoryRequestDTO request, String username);

    /**
     * Update an existing maternal history record (creates a new version).
     *
     * @param id       The ID of the maternal history to update
     * @param request  The updated maternal history data
     * @param username The username of the user updating the record
     * @return The updated maternal history response with incremented version
     */
    MaternalHistoryResponseDTO updateMaternalHistory(UUID id, MaternalHistoryRequestDTO request, String username);

    /**
     * Get a maternal history record by ID.
     *
     * @param id       The maternal history ID
     * @param username The username of the requesting user
     * @return The maternal history response
     */
    MaternalHistoryResponseDTO getMaternalHistoryById(UUID id, String username);

    /**
     * Get the current (most recent version) maternal history for a patient.
     *
     * @param patientId The patient ID
     * @param username  The username of the requesting user
     * @return The current maternal history response
     */
    MaternalHistoryResponseDTO getCurrentMaternalHistoryByPatientId(UUID patientId, String username);

    /**
     * Get all versions of maternal history for a patient.
     *
     * @param patientId The patient ID
     * @param username  The username of the requesting user
     * @return List of all maternal history versions, ordered by version number descending
     */
    List<MaternalHistoryResponseDTO> getAllVersionsByPatientId(UUID patientId, String username);

    /**
     * Get a specific version of maternal history for a patient.
     *
     * @param patientId     The patient ID
     * @param versionNumber The version number
     * @param username      The username of the requesting user
     * @return The maternal history response for the specified version
     */
    MaternalHistoryResponseDTO getMaternalHistoryByPatientIdAndVersion(
        UUID patientId,
        Integer versionNumber,
        String username
    );

    /**
     * Get maternal history records within a date range for a patient.
     *
     * @param patientId The patient ID
     * @param startDate The start date
     * @param endDate   The end date
     * @param username  The username of the requesting user
     * @return List of maternal history records within the date range
     */
    List<MaternalHistoryResponseDTO> getMaternalHistoryByDateRange(
        UUID patientId,
        LocalDateTime startDate,
        LocalDateTime endDate,
        String username
    );

    /**
     * Search maternal history records with comprehensive filters.
     *
     * @param hospitalId         Filter by hospital ID
     * @param patientId          Filter by patient ID
     * @param riskCategory       Filter by risk category (LOW, MODERATE, HIGH)
     * @param dataComplete       Filter by data completeness
     * @param reviewedByProvider Filter by provider review status
     * @param dateFrom           Filter by recorded date from
     * @param dateTo             Filter by recorded date to
     * @param pageable           Pagination parameters
     * @param username           The username of the requesting user
     * @return Page of maternal history records matching the criteria
     */
    Page<MaternalHistoryResponseDTO> searchMaternalHistory(
        UUID hospitalId,
        UUID patientId,
        String riskCategory,
        Boolean dataComplete,
        Boolean reviewedByProvider,
        LocalDateTime dateFrom,
        LocalDateTime dateTo,
        Pageable pageable,
        String username
    );

    /**
     * Get high-risk maternal history records for a hospital.
     *
     * @param hospitalId The hospital ID
     * @param pageable   Pagination parameters
     * @param username   The username of the requesting user
     * @return Page of high-risk maternal history records
     */
    Page<MaternalHistoryResponseDTO> getHighRiskMaternalHistory(
        UUID hospitalId,
        Pageable pageable,
        String username
    );

    /**
     * Get maternal history records pending provider review.
     *
     * @param hospitalId The hospital ID
     * @param pageable   Pagination parameters
     * @param username   The username of the requesting user
     * @return Page of maternal history records pending review
     */
    Page<MaternalHistoryResponseDTO> getPendingReview(
        UUID hospitalId,
        Pageable pageable,
        String username
    );

    /**
     * Get maternal history records requiring specialist referral.
     *
     * @param hospitalId The hospital ID
     * @param pageable   Pagination parameters
     * @param username   The username of the requesting user
     * @return Page of maternal history records requiring specialist referral
     */
    Page<MaternalHistoryResponseDTO> getRequiringSpecialistReferral(
        UUID hospitalId,
        Pageable pageable,
        String username
    );

    /**
     * Get maternal history records with psychosocial concerns.
     *
     * @param hospitalId The hospital ID
     * @param pageable   Pagination parameters
     * @param username   The username of the requesting user
     * @return Page of maternal history records with psychosocial concerns
     */
    Page<MaternalHistoryResponseDTO> getWithPsychosocialConcerns(
        UUID hospitalId,
        Pageable pageable,
        String username
    );

    /**
     * Mark a maternal history record as reviewed by a provider.
     *
     * @param id       The maternal history ID
     * @param username The username of the reviewing provider
     * @return The updated maternal history response
     */
    MaternalHistoryResponseDTO markAsReviewed(UUID id, String username);

    /**
     * Delete a maternal history record (soft delete by marking inactive).
     *
     * @param id       The maternal history ID
     * @param username The username of the user deleting the record
     */
    void deleteMaternalHistory(UUID id, String username);

    /**
     * Calculate risk score for a maternal history record.
     * This is called automatically during create/update but can be invoked manually.
     *
     * @param id       The maternal history ID
     * @param username The username of the requesting user
     * @return The updated maternal history with calculated risk score
     */
    MaternalHistoryResponseDTO calculateRiskScore(UUID id, String username);
}
