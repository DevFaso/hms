package com.example.hms.service.support;

import com.example.hms.config.SecurityConstants;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.HospitalUserDetails;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.TenantContextAccessor;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
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
 *       role, has every one of its hospital assignments at hospitals the
 *       caller administers, and, if it has a patient record, every
 *       registration of that record (any status) there too. "Every", not
 *       "any": a patient here who is an admin, or registered, elsewhere must
 *       not be handed to this hospital's admin, who could reset the password
 *       and sign in as them.</li>
 *   <li><b>Self</b>: any principal may read their own account, and edit its
 *       contact fields. The credential and status fields go through their own
 *       endpoints (see {@code UserServiceImpl.updateUser}).</li>
 *   <li><b>Discard an unclaimed patient account</b>: the compensation the
 *       patient-registration form sends when the patient row fails after the
 *       account was created. Only an account that is plainly that orphan
 *       qualifies (see {@link #canDelete}).</li>
 *   <li><b>Directory</b> (list / search): staff only, meaning a caller holding
 *       an active assignment in any role other than PATIENT, or a super-admin.
 *       Decided from the assignment table, not from token authorities: a
 *       Keycloak token also carries realm noise such as
 *       {@code ROLE_OFFLINE_ACCESS}, so "any role but PATIENT" read from the
 *       token would admit every patient.</li>
 * </ul>
 *
 * <p>Each public decision loads the caller's and the target's assignments
 * once. Callers answer a refused id-based request exactly as they answer a
 * missing one, so the refusal is not an existence oracle.
 */
@Component
@RequiredArgsConstructor
public class UserAccountAccess {

    /**
     * How long after its assignments were created the registration form's
     * compensation may still discard the account. The compensation fires
     * within the same form submit; the window only bounds how stale an orphan
     * it can reach. Measured on the assignments, not the account:
     * re-registering an account a previous compensation soft-deleted restores
     * the OLD row but creates fresh assignments (the delete removed them), and
     * that retry must still be able to compensate.
     */
    static final Duration UNCLAIMED_ACCOUNT_WINDOW = Duration.ofMinutes(30);

    private static final String SUPER_ADMIN = "SUPER_ADMIN";
    private static final String HOSPITAL_ADMIN = "HOSPITAL_ADMIN";
    private static final String PATIENT = "PATIENT";

    /** The admin-register roles, bare; the same constant feeds both controller annotations. */
    static final Set<String> REGISTRAR_ROLES = Arrays.stream(SecurityConstants.USER_REGISTRAR_AUTHORITIES.split(","))
        .map(role -> bare(role.replace("'", "")))
        .collect(Collectors.toUnmodifiableSet());

    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final StaffRepository staffRepository;
    private final TenantContextAccessor tenantContext;

    /**
     * The caller as the decisions see them, loaded once per decision.
     *
     * @param activeAssignments the caller's ACTIVE assignments
     */
    private record Caller(UUID id, boolean superAdmin, Set<String> bareAuthorities,
                          List<UserRoleHospitalAssignment> activeAssignments) {

        boolean presents(String bareRole) {
            return bareAuthorities.contains(bareRole);
        }

        /** Hospitals where the caller actively holds one of these roles. */
        Set<UUID> hospitalsWhereHolding(Set<String> bareRoles) {
            return activeAssignments.stream()
                .filter(a -> bareRoles.contains(roleCode(a.getRole())))
                .map(UserAccountAccess::hospitalIdOf)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        }
    }

    /** The caller's HMS user id, or empty when it cannot be established. */
    public Optional<UUID> currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        if (auth.getPrincipal() instanceof HospitalUserDetails details && details.getUserId() != null) {
            return Optional.of(details.getUserId());
        }
        if (auth instanceof JwtAuthenticationToken token) {
            // Keycloak maps the user attribute app_user_id to this claim; it is
            // the HMS user id. The subject is Keycloak's own id and matches no
            // users row, so it is deliberately not a fallback.
            UUID fromClaim = parseUuid(token.getToken().getClaimAsString("appUserId"));
            if (fromClaim != null) {
                return Optional.of(fromClaim);
            }
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
        return tenantContext.isSuperAdmin();
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
        Set<UUID> administered = administeredHospitals(caller());
        if (administered.isEmpty()) {
            return false;
        }
        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUserId(target.getId());
        return !holdsSuperAdmin(target, assignments)
            && assignments.stream().map(UserAccountAccess::hospitalIdOf).anyMatch(administered::contains);
    }

    /** May the caller edit another person's account, or restore it? */
    public boolean canAdminister(User target) {
        if (target == null) {
            return false;
        }
        if (isSuperAdmin()) {
            return true;
        }
        return administers(caller(), target, assignmentRepository.findByUserId(target.getId()));
    }

    /**
     * May the caller delete this account? An administrator of it may. So may a
     * registrar discarding the unclaimed patient account its own failed
     * registration just made: patient-form creates the account, then the
     * patient row, and deletes the account when the second call fails. That
     * requires every sign that the account is that orphan:
     * <ul>
     *   <li>the caller presents a registrar role (the roles admin-register admits);</li>
     *   <li>the account is live and has never signed in;</li>
     *   <li>it holds only PATIENT roles, and every assignment is at a hospital
     *       where the caller actively holds a REGISTRAR role (the caller's own
     *       patient assignments do not count) and was created within
     *       {@link #UNCLAIMED_ACCOUNT_WINDOW};</li>
     *   <li>no patient row and no staff row points at it.</li>
     * </ul>
     * An existing identity that admin-register merely re-used fails at least
     * one of these (it has signed in, or has a patient row, or another role).
     */
    public boolean canDelete(User target) {
        if (target == null) {
            return false;
        }
        if (isSuperAdmin()) {
            return true;
        }
        Caller caller = caller();
        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUserId(target.getId());
        return administers(caller, target, assignments) || isUnclaimedOrphan(caller, target, assignments);
    }

    /**
     * The user directory (list and search) is for staff. Throws
     * {@link AccessDeniedException} for everyone else, patients included.
     */
    public void requireDirectoryAccess() {
        if (isSuperAdmin()) {
            return;
        }
        boolean staff = caller().activeAssignments().stream()
            .map(a -> roleCode(a.getRole()))
            .anyMatch(code -> !code.isEmpty() && !PATIENT.equals(code));
        if (!staff) {
            throw new AccessDeniedException("Access denied");
        }
    }

    private Caller caller() {
        UUID id = currentUserId().orElse(null);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> authorities = auth == null ? Set.of() : auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(Objects::nonNull)
            .map(UserAccountAccess::bare)
            .collect(Collectors.toSet());
        List<UserRoleHospitalAssignment> active = id == null
            ? List.of()
            : assignmentRepository.findByUser_IdAndActiveTrue(id);
        return new Caller(id, isSuperAdmin(), authorities, active);
    }

    /** Hospitals where the caller holds an ACTIVE hospital-admin assignment, if they present the role at all. */
    private static Set<UUID> administeredHospitals(Caller caller) {
        if (caller.id() == null || !caller.presents(HOSPITAL_ADMIN)) {
            return Set.of();
        }
        return caller.hospitalsWhereHolding(Set.of(HOSPITAL_ADMIN));
    }

    private boolean administers(Caller caller, User target, List<UserRoleHospitalAssignment> assignments) {
        if (caller.superAdmin()) {
            return true;
        }
        Set<UUID> administered = administeredHospitals(caller);
        if (administered.isEmpty() || assignments.isEmpty() || holdsSuperAdmin(target, assignments)) {
            return false;
        }
        boolean assignedOnlyHere = assignments.stream()
            .map(UserAccountAccess::hospitalIdOf)
            .allMatch(hospitalId -> hospitalId != null && administered.contains(hospitalId));
        if (!assignedOnlyHere) {
            return false;
        }
        // A patient's footprint is also its registrations: one assignment here
        // and a registration elsewhere would let this admin reset the password
        // and read the other hospital's records as the patient.
        return registrationRepository.findHospitalIdsByPatientUserId(target.getId()).stream()
            .allMatch(hospitalId -> hospitalId != null && administered.contains(hospitalId));
    }

    private boolean isUnclaimedOrphan(Caller caller, User target, List<UserRoleHospitalAssignment> assignments) {
        if (caller.id() == null || caller.id().equals(target.getId()) || target.isDeleted()
                || REGISTRAR_ROLES.stream().noneMatch(caller::presents)) {
            return false;
        }
        if (target.getLastLoginAt() != null || target.getLastOidcLoginAt() != null || assignments.isEmpty()) {
            return false;
        }
        boolean onlyPatientGlobalRoles = target.getUserRoles().stream()
            .map(UserRole::getRole)
            .allMatch(role -> PATIENT.equals(roleCode(role)));
        if (!onlyPatientGlobalRoles) {
            return false;
        }
        Set<UUID> registrarHospitals = caller.hospitalsWhereHolding(REGISTRAR_ROLES);
        LocalDateTime windowStart = LocalDateTime.now().minus(UNCLAIMED_ACCOUNT_WINDOW);
        boolean freshPatientAssignmentsHere = assignments.stream().allMatch(a ->
            PATIENT.equals(roleCode(a.getRole()))
                && hospitalIdOf(a) != null
                && registrarHospitals.contains(hospitalIdOf(a))
                && isRecent(a, windowStart));
        return freshPatientAssignmentsHere
            && !patientRepository.existsByUserId(target.getId())
            && !staffRepository.existsByUserId(target.getId());
    }

    /** Any trace of super-admin on the target, active or not, global role or assignment. */
    private static boolean holdsSuperAdmin(User target, List<UserRoleHospitalAssignment> assignments) {
        boolean viaAssignment = assignments.stream()
            .anyMatch(a -> SUPER_ADMIN.equals(roleCode(a.getRole())));
        boolean viaGlobalRole = target.getUserRoles().stream()
            .anyMatch(ur -> SUPER_ADMIN.equals(roleCode(ur.getRole())));
        return viaAssignment || viaGlobalRole;
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

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
