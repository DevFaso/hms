package com.example.hms.service;

import com.example.hms.payload.dto.clinical.BirthPlanProviderReviewRequestDTO;
import com.example.hms.payload.dto.clinical.BirthPlanRequestDTO;
import com.example.hms.payload.dto.clinical.BirthPlanResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Service interface for Birth Plan operations.
 */
public interface BirthPlanService {

    /**
     * Create a new birth plan.
     */
    BirthPlanResponseDTO createBirthPlan(BirthPlanRequestDTO request, String username);

    /**
     * Update an existing birth plan.
     */
    BirthPlanResponseDTO updateBirthPlan(UUID id, BirthPlanRequestDTO request, String username);

    /**
     * Get a birth plan by ID.
     */
    BirthPlanResponseDTO getBirthPlanById(UUID id, String username);

    /**
     * Get all birth plans for a specific patient.
     */
    List<BirthPlanResponseDTO> getBirthPlansByPatientId(UUID patientId, String username);

    /**
     * Get the active (most recent) birth plan for a patient.
     */
    BirthPlanResponseDTO getActiveBirthPlan(UUID patientId, String username);

    /**
     * Search birth plans with filters.
     */
    Page<BirthPlanResponseDTO> searchBirthPlans(
        UUID hospitalId,
        UUID patientId,
        Boolean providerReviewed,
        LocalDate dueDateFrom,
        LocalDate dueDateTo,
        Pageable pageable,
        String username
    );

    /**
     * Provider review and co-sign birth plan.
     */
    BirthPlanResponseDTO providerReview(UUID id, BirthPlanProviderReviewRequestDTO review, String username);

    /**
     * Delete a birth plan.
     */
    void deleteBirthPlan(UUID id, String username);

    /**
     * Get birth plans pending review for a hospital.
     */
    Page<BirthPlanResponseDTO> getPendingReviews(UUID hospitalId, Pageable pageable, String username);
}
