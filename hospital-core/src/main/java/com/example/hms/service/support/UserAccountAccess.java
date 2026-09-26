package com.example.hms.service.support;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.HospitalContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Who may read or change a user ACCOUNT through {@code /users}.
 *
 * <p>Before this existed, {@code GET/PUT/DELETE /users/{id}} and the directory
 * reads carried no guard at all: any authenticated principal, a patient's
 * mobile token included, could set another account's password, rename,
 * deactivate or delete it, and list every account. The rules live here, in
 * the service layer, so a future controller over {@code UserService} cannot
 * route around them.
 *
 * <p>The decisions:
 * <ul>
 *   <li><b>Administer</b> (edit another account, delete, restore): a
 *       super-admin; or a hospital admin whose target holds NO super-admin
 *       role and has every one of its hospital assignments at hospitals the
 *       caller administers. "Every", not "any": a patient registered at the
 *       admin's hospital who is also an admin elsewhere must not be taken over
 *       by this hospital's admin.</li>
 *   <li><b>Self</b>: any principal may read their own account, and edit its
 *       contact fields. The credential and status fields go through their own
 *       endpoints (see {@code UserServiceImpl.updateUser}).</li>
 *   <li><b>Discard an unclaimed patient account</b>: the compensation the
 *       patient-registration form sends when the patient row fails after the
 *       account was created. Only an account that is plainly that orphan
 *       qualifies (see {@link #canDiscardUnclaimedPatientAccount}).</li>
 *   <li><b>Directory</b> (list / search): staff only — a caller holding an
 *       active assignment in any role other than PATIENT, or a super-admin.
 *       Decided from the assignment table, not from token authorities: a
 *       Keycloak token also carries realm noise such as
 *       {@code ROLE_OFFLINE_ACCESS}, so "any role but PATIENT" read from the
 *       token would admit every patient.</li>
 * </ul>
 *
 * <p>Callers answer a refused id-based request exactly as they answer a
 * missing one, so the refusal is not an existence oracle.
 */
@Component
@RequiredArgsConstructor
public class UserAccountAccess {

    /**
     * How long after its assignments were created the registration form's
     * compensation may still discard the account. The compensation fires
     * within the same form submit; the window only bounds how stale an orphan
     * it can reach. Measured on the assignments, not the account: re-registering
     * an account a previous compensation soft-deleted restores the OLD row but
     * creates fresh assignments (the delete removed them), and that retry must
     * still be able to compensate.
     */
    static final Duration UNCLAIMED_ACCOUNT_WINDOW = Duration.ofMinutes(30);

    private static final String SUPER_ADMIN = "SUPER_ADMIN";
    private static final String HOSPITAL_ADMIN = "HOSPITAL_ADMIN";
    private static final String PATIENT = "PATIENT";

    /** The roles admin-register admits; the same roles send the compensation delete. */
    private static final Set<String> REGISTRAR_ROLES =
        Set.of(HOSPITAL_ADMIN, "RECEPTIONIST", "DOCTOR", "NURSE", "MIDWIFE");

    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final PatientRepository patientRepository;
    private final StaffRepository staffRepository;
    private final ControllerAuthUtils authUtils;

    /** The caller's HMS user id, or empty when it cannot be established. */
    public Optional<UUID> currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        Optional<UUID> resolved = authUtils.resolveUserId(auth);
        if (resolved.isPresent()) {
            return resolved;
        }
        return Optional.ofNullable(HospitalContextHolder.getContextOrEmpty().getPrincipalUserId());
    }

    public boolean isSelf(User target) {
        return target != null && currentUserId().map(target.getId()::equals).orElse(false);
    }

    /**
     * The discrete super-admin flag of the request's hospital context, not the
     * authorities collection, which is inflated for other purposes (see
     * {@code RoleValidator.isSuperAdminFromJwtClaim}).
     */
    public boolean isSuperAdmin() {
        return HospitalContextHolder.getContextOrEmpty().isSuperAdmin();
    }

    /**
     * May the caller read this account's full record? Self; a super-admin; or
     * a hospital admin of any hospital the account is assigned to, unless the
     * account is a super-admin's.
     */
    public boolean canView(User target) {
        if (target == null) {
            return false;
        }
        if (isSelf(target) || isSuperAdmin()) {
            return true;
        }
        Set<UUID> administered = administeredHospitalIds();
        if (administered.isEmpty()) {
            return false;
        }
        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUserId(target.getId());
        return !holdsSuperAdmin(target, assignments)
            && assignments.stream().map(UserAccountAccess::hospitalIdOf).anyMatch(administered::contains);
    }

    /** May the caller edit another person's account, delete it or restore it? */
    public boolean canAdminister(User target) {
        if (target == null) {
            return false;
        }
        if (isSuperAdmin()) {
            return true;
        }
        Set<UUID> administered = administeredHospitalIds();
        if (administered.isEmpty()) {
            return false;
        }
        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUserId(target.getId());
        if (assignments.isEmpty() || holdsSuperAdmin(target, assignments)) {
            return false;
        }
        return assignments.stream()
            .map(UserAccountAccess::hospitalIdOf)
            .allMatch(hospitalId -> hospitalId != null && administered.contains(hospitalId));
    }

    /**
     * The patient-registration form creates the account first, then the
     * patient row; when the second call fails it deletes the account it just
     * made. A registrar may delete an account only when every sign says it is
     * that orphan:
     * <ul>
     *   <li>the caller holds a registrar role (the roles admin-register admits);</li>
     *   <li>the account is live and has never signed in;</li>
     *   <li>it holds only PATIENT roles, and every assignment is at a hospital
     *       the caller is actively assigned to and was created within
     *       {@link #UNCLAIMED_ACCOUNT_WINDOW};</li>
     *   <li>no patient row and no staff row points at it.</li>
     * </ul>
     * An existing identity that admin-register merely re-used fails at least
     * one of these (it has signed in, or has a patient row, or another role).
     */
    public boolean canDiscardUnclaimedPatientAccount(User target) {
        if (target == null || target.isDeleted() || !holdsAuthority(REGISTRAR_ROLES)) {
            return false;
        }
        if (target.getLastLoginAt() != null || target.getLastOidcLoginAt() != null) {
            return false;
        }
        UUID callerId = currentUserId().orElse(null);
        if (callerId == null || callerId.equals(target.getId())) {
            return false;
        }
        boolean onlyPatientGlobalRoles = target.getUserRoles().stream()
            .map(UserRole::getRole)
            .allMatch(role -> PATIENT.equals(roleCode(role)));
        if (!onlyPatientGlobalRoles) {
            return false;
        }
        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUserId(target.getId());
        if (assignments.isEmpty()) {
            return false;
        }
        Set<UUID> callerHospitals = assignmentRepository.findByUser_IdAndActiveTrue(callerId).stream()
            .map(UserAccountAccess::hospitalIdOf)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        LocalDateTime windowStart = LocalDateTime.now().minus(UNCLAIMED_ACCOUNT_WINDOW);
        boolean freshPatientAssignmentsHere = assignments.stream().allMatch(a ->
            PATIENT.equals(roleCode(a.getRole()))
                && hospitalIdOf(a) != null
                && callerHospitals.contains(hospitalIdOf(a))
                && isRecent(a, windowStart));
        if (!freshPatientAssignmentsHere) {
            return false;
        }
        return !patientRepository.existsByUserId(target.getId())
            && staffRepository.findByUserId(target.getId()).isEmpty();
    }

    /**
     * The user directory (list and search) is for staff. Throws
     * {@link AccessDeniedException} for everyone else, patients included.
     */
    public void requireDirectoryAccess() {
        if (isSuperAdmin()) {
            return;
        }
        UUID callerId = currentUserId()
            .orElseThrow(() -> new AccessDeniedException("Access denied"));
        boolean staff = assignmentRepository.findByUser_IdAndActiveTrue(callerId).stream()
            .map(a -> roleCode(a.getRole()))
            .anyMatch(code -> !code.isEmpty() && !PATIENT.equals(code));
        if (!staff) {
            throw new AccessDeniedException("Access denied");
        }
    }

    /** Hospitals where the caller holds an ACTIVE hospital-admin assignment, if they present the role at all. */
    private Set<UUID> administeredHospitalIds() {
        if (!holdsAuthority(Set.of(HOSPITAL_ADMIN))) {
            return Set.of();
        }
        UUID callerId = currentUserId().orElse(null);
        if (callerId == null) {
            return Set.of();
        }
        return assignmentRepository.findByUser_IdAndActiveTrue(callerId).stream()
            .filter(a -> HOSPITAL_ADMIN.equals(roleCode(a.getRole())))
            .map(UserAccountAccess::hospitalIdOf)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    /** Any trace of super-admin on the target, active or not, global role or assignment. */
    private static boolean holdsSuperAdmin(User target, List<UserRoleHospitalAssignment> assignments) {
        boolean viaAssignment = assignments.stream()
            .anyMatch(a -> SUPER_ADMIN.equals(roleCode(a.getRole())));
        boolean viaGlobalRole = target.getUserRoles().stream()
            .anyMatch(ur -> SUPER_ADMIN.equals(roleCode(ur.getRole())));
        return viaAssignment || viaGlobalRole;
    }

    private static boolean holdsAuthority(Set<String> bareRoles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(Objects::nonNull)
            .map(UserAccountAccess::bare)
            .anyMatch(bareRoles::contains);
    }

    private static boolean isRecent(UserRoleHospitalAssignment assignment, LocalDateTime windowStart) {
        LocalDateTime created = assignment.getCreatedAt() != null
            ? assignment.getCreatedAt()
            : assignment.getAssignedAt();
        return created != null && !created.isBefore(windowStart);
    }

    private static UUID hospitalIdOf(UserRoleHospitalAssignment assignment) {
        return assignment.getHospital() == null ? null : assignment.getHospital().getId();
    }

    /** The role's code without its {@code ROLE_} prefix, upper-case; "" when unknown. */
    private static String roleCode(Role role) {
        if (role == null) {
            return "";
        }
        String raw = role.getCode() != null ? role.getCode() : role.getName();
        return raw == null ? "" : bare(raw);
    }

    private static String bare(String role) {
        String upper = role.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("ROLE_") ? upper.substring("ROLE_".length()) : upper;
    }
}
