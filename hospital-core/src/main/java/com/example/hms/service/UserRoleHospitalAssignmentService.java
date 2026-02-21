package com.example.hms.service;

import com.example.hms.payload.dto.AssignmentMinimalDTO;
import com.example.hms.payload.dto.UserRoleHospitalAssignmentRequestDTO;
import com.example.hms.payload.dto.UserRoleHospitalAssignmentResponseDTO;
import com.example.hms.payload.dto.assignment.AssignmentSearchCriteria;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBatchResponseDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBulkImportRequestDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentBulkImportResponseDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentPublicViewDTO;
import com.example.hms.payload.dto.assignment.UserRoleAssignmentMultiRequestDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Service boundary for managing user-role assignments within hospitals.
 */
public interface UserRoleHospitalAssignmentService {

    /**
     * Create a new user-role-hospital assignment.
     */
    UserRoleHospitalAssignmentResponseDTO assignRole(UserRoleHospitalAssignmentRequestDTO requestDTO);

    /**
     * Update an existing assignment.
     */
    UserRoleHospitalAssignmentResponseDTO updateAssignment(UUID id, UserRoleHospitalAssignmentRequestDTO dto);

    /**
     * Fetch a single assignment by ID.
     */
    UserRoleHospitalAssignmentResponseDTO getAssignmentById(UUID id);

    /**
     * List assignments with pagination.
     */
    Page<UserRoleHospitalAssignmentResponseDTO> getAllAssignments(Pageable pageable);

    /**
     * List assignments with pagination and optional filters.
     *
     * @param pageable Paging information
     * @param criteria Optional structured filters (hospital, role, active flag, search, code)
     * @return Page of assignments satisfying the filters
     */
    Page<UserRoleHospitalAssignmentResponseDTO> getAllAssignments(Pageable pageable, AssignmentSearchCriteria criteria);

    /**
     * Delete a single assignment by ID.
     */
    void deleteAssignment(UUID id);

    /**
     * Delete all assignments for a specific user.
     */
    void deleteAllAssignmentsForUser(UUID userId);

    /**
     * Delete a role (only if unassigned).
     */
    void deleteRole(UUID roleId);

    /**
     * Check if a user already has a given role in a hospital.
     */
    boolean isRoleAlreadyAssigned(UUID userId, UUID hospitalId, UUID roleId);

    /**
     * Assign a user/role combination across multiple hospitals or organizations in one request.
     */
    UserRoleAssignmentBatchResponseDTO assignRoleToMultipleScopes(UserRoleAssignmentMultiRequestDTO requestDTO);

    /**
     * Regenerate an assignment code (and optional notifications).
     */
    UserRoleHospitalAssignmentResponseDTO regenerateAssignmentCode(UUID assignmentId, boolean resendNotifications);

    /**
     * Confirm that the actor who created the assignment has validated the confirmation code.
     */
    UserRoleHospitalAssignmentResponseDTO confirmAssignment(UUID assignmentId, String confirmationCode);

    /**
     * Fetch a public view of an assignment by its human-facing assignment code.
     */
    UserRoleAssignmentPublicViewDTO getAssignmentPublicView(String assignmentCode);

    /**
     * Self-service verification: the assignee submits their confirmation code.
     * This is the unauthenticated endpoint used from the onboarding email link.
     * On success the assignment is marked as verified and the public view is returned.
     */
    UserRoleAssignmentPublicViewDTO verifyAssignmentByCode(String assignmentCode, String confirmationCode);

    /**
     * Bulk import assignments using a CSV payload.
     */
    UserRoleAssignmentBulkImportResponseDTO bulkImportAssignments(UserRoleAssignmentBulkImportRequestDTO requestDTO);

    /**
     * Get minimal assignment data for dropdowns.
     */
    List<AssignmentMinimalDTO> getMinimalAssignments();

    /**
     * Resend the email + SMS notification for an existing assignment.
     * Used by the {@code AssignmentCreatedEventListener} (AFTER_COMMIT) and
     * the admin "resend notification" endpoint.
     */
    void sendNotifications(UUID assignmentId);
}
