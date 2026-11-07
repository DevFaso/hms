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

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Service boundary for managing user-role assignments within hospitals.
 */
public interface UserRoleHospitalAssignmentService {

    /**
     * Create a new user-role-hospital assignment on an {@code /assignments}
     * request: the caller may grant only what
     * {@code UserAccountAccess.requireMayGrant} allows, at a hospital it
     * allows (403 otherwise, nothing saved).
     */
    UserRoleHospitalAssignmentResponseDTO assignRole(UserRoleHospitalAssignmentRequestDTO requestDTO);

    /**
     * Create an assignment for an account being created, without the caller
     * grant check: for {@code UserService} only, whose callers have already
     * decided the grant (admin-register's {@code requireMayGrant}, public
     * patient self-registration, the first-user bootstrap). Never call it from
     * a controller.
     */
    UserRoleHospitalAssignmentResponseDTO assignRoleOnAccountCreation(UserRoleHospitalAssignmentRequestDTO requestDTO);

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
     * @deprecated Prefer {@link #deactivateAssignment(UUID)} to preserve history.
     */
    @Deprecated(since = "1.1", forRemoval = false)
    void deleteAssignment(UUID id);

    /**
     * Soft-deactivate an assignment: sets {@code active = false}.
     * The record is preserved for audit and historical integrity.
     * This is the preferred alternative to hard-deleting assignments.
     */
    void deactivateAssignment(UUID id);

    /**
     * The provider staff page's deactivation ({@code /provider/staff}): the
     * rows are retired as {@link #deactivateAssignment} retires one, under the
     * caller's PROVIDER STAFF scope ({@code UserAccountAccess.providerStaffScope},
     * never the general one), read once; every row is checked, a super-admin's
     * account shielded, before any is changed. A row out of scope answers as a
     * missing id and nothing is changed.
     */
    void deactivateProviderStaffAssignments(Collection<UUID> ids);

    /**
     * The provider staff page's re-invitation: {@link #regenerateAssignmentCode}
     * for several rows, under the same provider staff scope and
     * all-checked-first rule as {@link #deactivateProviderStaffAssignments}.
     */
    void regenerateProviderStaffAssignmentCodes(Collection<UUID> ids, boolean resendNotifications);

    /**
     * Retire all assignments of a specific user by DEACTIVATING them. The rows
     * are kept: clinical records keep the assignment they were recorded under.
     * No caller check: for {@code UserService.deleteUser}, which has already
     * decided the caller may delete the account. Never call it from a controller.
     */
    void deleteAllAssignmentsForUser(UUID userId);

    /**
     * A restored account that was never activated: every invitation it never
     * confirmed gets a fresh assignment and confirmation code, mailed through
     * the usual assignment notification (the delete revoked the old codes).
     * The rows stay inactive until the holder enters the new code. No caller
     * check: for {@code UserService.restoreUser}, which has already decided the
     * caller may administer the account. Never call it from a controller.
     * Returns how many invitations were reissued.
     */
    int reissuePendingInvitationsForRestoredUser(UUID userId);

    /**
     * {@code DELETE /assignments/user/{userId}}: retire the user's assignments
     * the caller may change. A super-admin: every row. A hospital admin: the
     * rows at hospitals they administer, never an admin role's row, and none
     * of a super-admin's account.
     */
    void retireAssignmentsForUserWithinCallerScope(UUID userId);

    /**
     * QA D907: deactivating a staff record ends the access it stood for at its
     * hospital. Retires, as {@link #deactivateAssignment} does, the record's
     * own assignment and every other staff (non-patient) assignment the holder
     * has at that hospital, active or still waiting on its code. The account
     * itself stays on: its holder may work at another hospital.
     *
     * <p>Refused (409), with nothing changed, for the caller's own account,
     * when one of those rows is out of the caller's reach (an administrator
     * role, for a hospital admin) and when it would leave no live
     * super-admin. Each row switched off is audited as {@code ROLE_REVOKED}
     * once the transaction commits. For {@code StaffService.deleteStaff},
     * which has already decided the caller may change the staff record.
     *
     * @return how many assignments were switched off
     */
    int retireStaffAccessAtHospital(UUID userId, UUID hospitalId, UUID staffAssignmentId);

    /**
     * QA D902: refuses (409) when switching this account off would leave the
     * platform with no live super-admin assignment. For
     * {@code UserService.updateUser}, before it deactivates the account.
     */
    void requireSuperAdminRemainsWithoutUser(UUID userId);

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
     * QA D1123: activation by the registered phone number and the one-time
     * code from the SMS ("I have a code"), for a holder with no e-mail link.
     */
    UserRoleAssignmentPublicViewDTO verifyAssignmentByPhone(String phone, String confirmationCode);

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

    /**
     * {@link #sendNotifications(UUID)}, skipping the e-mail when the new
     * user's welcome mail already carries this assignment's code.
     */
    void sendNotifications(UUID assignmentId, boolean emailCoveredByWelcome);

    /**
     * The admin "resend notification" endpoint: {@link #sendNotifications}
     * for a row the caller may change; any other row answers as a missing one.
     */
    void resendNotifications(UUID assignmentId);

    /**
     * QA D418: the anonymous "send me a new activation code" request. Issues a
     * fresh code for a pending assignment and sends it (e-mail and/or SMS),
     * at most once per {@code cooldown} per assignment across every instance
     * (the claim is a conditional UPDATE on the row). Returns whether a code
     * went out; a revoked, active or recently re-sent row returns false.
     */
    boolean resendPendingActivationCode(UUID assignmentId, java.time.Duration cooldown);
}
