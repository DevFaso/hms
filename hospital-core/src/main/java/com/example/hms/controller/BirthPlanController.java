package com.example.hms.controller;

import com.example.hms.payload.dto.clinical.BirthPlanProviderReviewRequestDTO;
import com.example.hms.payload.dto.clinical.BirthPlanRequestDTO;
import com.example.hms.payload.dto.clinical.BirthPlanResponseDTO;
import com.example.hms.service.BirthPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * REST Controller for managing Birth Plans.
 * Provides endpoints for creating, updating, retrieving, and reviewing birth plans.
 * Supports both patient self-service and provider-assisted workflows.
 */
@RestController
@RequestMapping("/api/birth-plans")
@Tag(name = "Birth Plan Management", description = "Endpoints for creating, updating, and managing patient birth plans with provider review workflow.")
@RequiredArgsConstructor
public class BirthPlanController {

    private final BirthPlanService birthPlanService;

    /**
     * Helper method to extract username from Spring Security Authentication.
     */
    private String getUsername(Authentication authentication) {
        return authentication != null ? authentication.getName() : null;
    }

    /**
     * Create a new birth plan.
     * Patients can create their own plans, providers can create on behalf of patients.
     *
     * @param request Birth plan data
     * @param authentication Spring Security authentication
     * @return Created birth plan with HTTP 201 status
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'MIDWIFE', 'NURSE', 'PATIENT')")
    @Operation(summary = "Create a new birth plan", 
               description = "Allows patients to create their own birth plans or providers to create on behalf of patients.")
    public ResponseEntity<BirthPlanResponseDTO> createBirthPlan(
            @Valid @RequestBody BirthPlanRequestDTO request,
            Authentication authentication) {
        
        BirthPlanResponseDTO response = birthPlanService.createBirthPlan(request, getUsername(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Update an existing birth plan.
     * Patients can update their own plans, providers can update plans within their scope.
     * Updating a previously reviewed plan will reset the provider review status.
     *
     * @param id Birth plan ID
     * @param request Updated birth plan data
     * @param authentication Spring Security authentication
     * @return Updated birth plan with HTTP 200 status
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'MIDWIFE', 'NURSE', 'PATIENT')")
    @Operation(summary = "Update a birth plan", 
               description = "Updates an existing birth plan. If the plan was previously reviewed, the review status will be reset.")
    public ResponseEntity<BirthPlanResponseDTO> updateBirthPlan(
            @PathVariable UUID id,
            @Valid @RequestBody BirthPlanRequestDTO request,
            Authentication authentication) {
        
        BirthPlanResponseDTO response = birthPlanService.updateBirthPlan(id, request, getUsername(authentication));
        return ResponseEntity.ok(response);
    }

    /**
     * Get a specific birth plan by ID.
     * Access is controlled based on user role and ownership.
     *
     * @param id Birth plan ID
     * @param authentication Spring Security authentication
     * @return Birth plan details with HTTP 200 status
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'MIDWIFE', 'NURSE', 'PATIENT')")
    @Operation(summary = "Get birth plan by ID", 
               description = "Retrieves a specific birth plan. Access is controlled based on user role and ownership.")
    public ResponseEntity<BirthPlanResponseDTO> getBirthPlanById(
            @PathVariable UUID id,
            Authentication authentication) {
        
        BirthPlanResponseDTO response = birthPlanService.getBirthPlanById(id, getUsername(authentication));
        return ResponseEntity.ok(response);
    }

    /**
     * Get all birth plans for a specific patient.
     * Patients can only view their own plans, providers can view plans within their scope.
     *
     * @param patientId Patient ID
     * @param authentication Spring Security authentication
     * @return List of birth plans for the patient with HTTP 200 status
     */
    @GetMapping("/patient/{patientId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'MIDWIFE', 'NURSE', 'PATIENT')")
    @Operation(summary = "Get birth plans by patient ID", 
               description = "Retrieves all birth plans for a specific patient. Patients can only view their own plans.")
    public ResponseEntity<List<BirthPlanResponseDTO>> getBirthPlansByPatientId(
            @PathVariable UUID patientId,
            Authentication authentication) {
        
        List<BirthPlanResponseDTO> response = birthPlanService.getBirthPlansByPatientId(patientId, getUsername(authentication));
        return ResponseEntity.ok(response);
    }

    /**
     * Get the most recent (active) birth plan for a specific patient.
     * Returns null if no birth plan exists.
     *
     * @param patientId Patient ID
     * @param authentication Spring Security authentication
     * @return Most recent birth plan or HTTP 204 if none exists
     */
    @GetMapping("/patient/{patientId}/active")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'MIDWIFE', 'NURSE', 'PATIENT')")
    @Operation(summary = "Get active birth plan for patient", 
               description = "Retrieves the most recent birth plan for a specific patient. Returns 204 No Content if no plan exists.")
    public ResponseEntity<BirthPlanResponseDTO> getActiveBirthPlan(
            @PathVariable UUID patientId,
            Authentication authentication) {
        
        BirthPlanResponseDTO response = birthPlanService.getActiveBirthPlan(patientId, getUsername(authentication));
        
        if (response == null) {
            return ResponseEntity.noContent().build();
        }
        
        return ResponseEntity.ok(response);
    }

    /**
     * Search birth plans with filters.
     * Provider-only operation with optional filters for hospital, patient, review status, and due date range.
     *
     * @param hospitalId Filter by hospital ID (required for hospital admins)
     * @param patientId Filter by patient ID
     * @param providerReviewed Filter by review status (true/false)
     * @param dueDateFrom Filter by due date from (inclusive)
     * @param dueDateTo Filter by due date to (inclusive)
     * @param page Page number (default 0)
     * @param size Page size (default 20)
     * @param sort Sort field (default createdAt)
     * @param direction Sort direction (default DESC)
     * @param authentication Spring Security authentication
     * @return Paginated search results with HTTP 200 status
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'MIDWIFE', 'NURSE')")
    @Operation(summary = "Search birth plans", 
               description = "Provider-only operation to search birth plans with various filters and pagination.")
    public ResponseEntity<Page<BirthPlanResponseDTO>> searchBirthPlans(
            @Parameter(description = "Filter by hospital ID") 
            @RequestParam(required = false) UUID hospitalId,
            
            @Parameter(description = "Filter by patient ID") 
            @RequestParam(required = false) UUID patientId,
            
            @Parameter(description = "Filter by review status") 
            @RequestParam(required = false) Boolean providerReviewed,
            
            @Parameter(description = "Filter by due date from (format: yyyy-MM-dd)") 
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateFrom,
            
            @Parameter(description = "Filter by due date to (format: yyyy-MM-dd)") 
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateTo,
            
            @Parameter(description = "Page number (0-based)") 
            @RequestParam(defaultValue = "0") int page,
            
            @Parameter(description = "Page size") 
            @RequestParam(defaultValue = "20") int size,
            
            @Parameter(description = "Sort field") 
            @RequestParam(defaultValue = "createdAt") String sort,
            
            @Parameter(description = "Sort direction (ASC or DESC)") 
            @RequestParam(defaultValue = "DESC") String direction,
            
            Authentication authentication) {
        
        Sort.Direction sortDirection = "ASC".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, sort));
        
        Page<BirthPlanResponseDTO> response = birthPlanService.searchBirthPlans(
                hospitalId, 
                patientId, 
                providerReviewed, 
                dueDateFrom, 
                dueDateTo, 
                pageable, 
                getUsername(authentication)
        );
        
        return ResponseEntity.ok(response);
    }

    /**
     * Provider review and co-signature of a birth plan.
     * Only doctors and midwives can review and co-sign birth plans.
     *
     * @param id Birth plan ID
     * @param request Provider review details (reviewed status, signature, comments)
     * @param authentication Spring Security authentication
     * @return Updated birth plan with review information with HTTP 200 status
     */
    @PostMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'DOCTOR', 'MIDWIFE')")
    @Operation(summary = "Provider review and co-sign birth plan", 
               description = "Allows doctors and midwives to review and co-sign a birth plan with their signature and comments.")
    public ResponseEntity<BirthPlanResponseDTO> providerReview(
            @PathVariable UUID id,
            @Valid @RequestBody BirthPlanProviderReviewRequestDTO request,
            Authentication authentication) {
        
        BirthPlanResponseDTO response = birthPlanService.providerReview(id, request, getUsername(authentication));
        return ResponseEntity.ok(response);
    }

    /**
     * Delete a birth plan.
     * Patients can delete their own plans, providers can delete plans within their scope.
     *
     * @param id Birth plan ID
     * @param authentication Spring Security authentication
     * @return HTTP 204 No Content on successful deletion
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'MIDWIFE', 'PATIENT')")
    @Operation(summary = "Delete a birth plan", 
               description = "Deletes a birth plan. Access is controlled based on user role and ownership.")
    public ResponseEntity<Void> deleteBirthPlan(
            @PathVariable UUID id,
            Authentication authentication) {
        
        birthPlanService.deleteBirthPlan(id, getUsername(authentication));
        return ResponseEntity.noContent().build();
    }

    /**
     * Get birth plans pending provider review for a hospital.
     * Provider-only operation to retrieve unreviewed birth plans.
     *
     * @param hospitalId Hospital ID (optional, defaults to user's hospital)
     * @param page Page number (default 0)
     * @param size Page size (default 20)
     * @param authentication Spring Security authentication
     * @return Paginated list of pending birth plans with HTTP 200 status
     */
    @GetMapping("/pending-review")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'MIDWIFE')")
    @Operation(summary = "Get birth plans pending review", 
               description = "Provider-only operation to retrieve birth plans that require provider review for a specific hospital.")
    public ResponseEntity<Page<BirthPlanResponseDTO>> getPendingReviews(
            @Parameter(description = "Hospital ID (optional, defaults to user's hospital)") 
            @RequestParam(required = false) UUID hospitalId,
            
            @Parameter(description = "Page number (0-based)") 
            @RequestParam(defaultValue = "0") int page,
            
            @Parameter(description = "Page size") 
            @RequestParam(defaultValue = "20") int size,
            
            Authentication authentication) {
        
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        
        Page<BirthPlanResponseDTO> response = birthPlanService.getPendingReviews(
                hospitalId, 
                pageable, 
                getUsername(authentication)
        );
        
        return ResponseEntity.ok(response);
    }
}
