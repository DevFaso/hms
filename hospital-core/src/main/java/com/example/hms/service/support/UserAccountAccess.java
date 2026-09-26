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
import com.example.hms.security.RoleExpansion;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Who may read, change, delete or create a user ACCOUNT through {@code /users}.
 *
 * <p>Before this existed, {@code GET/PUT/DELETE /users/{id}} and the directory
 * reads carried no guard at all: any authenticated principal, a patient's
 * mobile token included, could set another account's password, rename,
 * deactivate or delete it, and list every account; and any registrar could
 * register an account in ANY role at ANY hospital, a platform super-admin
 * included. The rules live here, in the service layer, so a future
 * controller over {@code UserService} cannot route around them.
 *
 * <p>The decisions:
 * <ul>
 *   <li><b>Administer</b> (edit another account, delete, restore): a
 *       super-admin; or a hospital admin whose target holds NO admin role
 *       (super-admin, hospital admin or admin, active or not), has every one
 *       of its hospital assignments at hospitals the caller administers, and,
 *       if it has a patient record, every registration of that record (any
 *       status) there too. "Every", not "any": a patient here who is an admin,
 *       or registered, elsewhere must not be handed to this hospital's admin,
 *       who could reset the password and sign in as them. Admins are
 *       administered by the super-admin only, so one hospital admin cannot
 *       take over a peer.</li>
 *   <li><b>Grant</b> (admin-register): a super-admin grants any role at any
 *       hospital; a hospital admin grants non-admin roles at hospitals they
 *       administer; every other registrar grants PATIENT only, at a hospital
 *       where they hold a registrar role.</li>
 *   <li><b>Self</b>: any principal may read their own account, and edit its
 *       contact fields. The credential and status fields go through their own
 *       endpoints (see {@code UserServiceImpl.updateUser}).</li>
 *   <li><b>Discard an unclaimed patient account</b>: the compensation the
 *       patient-registration form sends when the patient row fails after the
 *       account was created (see {@link #canDelete}).</li>
 *   <li><b>Directory</b> (list / search): staff only, meaning a caller holding
 *       an active assignment in any role other than PATIENT, or a super-admin.
 *       Decided from the assignment table, not from token authorities: a
 *       Keycloak token also carries realm noise such as
 *       {@code ROLE_OFFLINE_ACCESS}, so "any role but PATIENT" read from the
 *       token would admit every patient.</li>
 * </ul>
 *
 * <p>Role codes are compared after {@link RoleExpansion}, on the token's
 * authorities and on the caller's assignment codes alike, so a surgeon who is
 * admitted as DOCTOR by the annotation is a DOCTOR here too.
 *
 * <p>Each public decision builds the caller once. Callers answer a refused
 * id-based request exactly as they answer a missing one, so the refusal is not
 * an existence oracle.
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

    /** Roles only a super-admin grants, and whose holders only a super-admin administers. */
    static final Set<String> ADMIN_ROLES = Set.of(SUPER_ADMIN, HOSPITAL_ADMIN, "ADMIN");

    /** The admin-register roles, bare; the same constant feeds the annotations and SecurityConfig. */
    static final Set<String> REGISTRAR_ROLES = Arrays.stream(
            SecurityConstants.authorities(SecurityConstants.USER_REGISTRAR_AUTHORITIES))
        .map(UserAccountAccess::bare)
        .collect(Collectors.toUnmodifiableSet());

    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final StaffRepository staffRepository;
    private final RoleValidator roleValidator;

    /**
     * The caller as one decision sees them, built once per decision.
     *
     * @param activeAssignments the caller's ACTIVE assignments; empty for a
     *                          super-admin, whom no rule needs them for
     */
    private record Caller(UUID id, boolean superAdmin, Set<String> bareAuthorities,
                          List<UserRoleHospitalAssignment> activeAssignments) {

        boolean presentsAny(Set<String> bareRoles) {
            return bareAuthorities.stream().anyMatch(bareRoles::contains);
        }

        /** Hospitals where the caller actively holds one of these roles, after RoleExpansion. */
        Set<UUID> hospitalsWhereHolding(Set<String> bareRoles) {
            return activeAssignments.stream()
                .filter(a -> expandedCodes(a.getRole()).stream().anyMatch(bareRoles::contains))
                .map(UserAccountAccess::hospitalIdOf)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        }
    }

    /**
     * The caller's HMS user id, from {@link RoleValidator#getCurrentUserId()}.
     * That resolver reads the password-login principal only; a Keycloak token
     * is not identified yet (the resolver work is batch 2), so on the OIDC
     * path self-service and the directory fail closed.
     */
    public Optional<UUID> currentUserId() {
        return Optional.ofNullable(roleValidator.getCurrentUserId());
    }

    public boolean isSelf(User target) {
        return target != null && currentUserId().map(target.getId()::equals).orElse(false);
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
        Caller caller = caller();
        if (caller.superAdmin() || target.getId().equals(caller.id())) {
            return true;
        }
        Set<UUID> administered = administeredHospitals(caller);
        if (administered.isEmpty()) {
            return false;
        }
        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUserId(target.getId());
        return !holdsAny(target, assignments, Set.of(SUPER_ADMIN))
            && assignments.stream().map(UserAccountAccess::hospitalIdOf).anyMatch(administered::contains);
    }

    /** May the caller edit another person's account, or restore it? */
    public boolean canAdminister(User target) {
        if (target == null) {
            return false;
        }
        Caller caller = caller();
        return caller.superAdmin()
            || administers(caller, target, assignmentRepository.findByUserId(target.getId()));
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
        Caller caller = caller();
        if (caller.superAdmin()) {
            return true;
        }
        List<UserRoleHospitalAssignment> assignments = assignmentRepository.findByUserId(target.getId());
        return administers(caller, target, assignments) || isUnclaimedOrphan(caller, target, assignments);
    }

    /**
     * May the caller register an account in these roles? Checked in two steps
     * over one caller: this one, on the role set alone, runs before anything
     * about the request is looked up; {@link Grant#requireAt} then checks the
     * hospital the registration resolves to.
     * <ul>
     *   <li>super-admin: any role, any hospital (or none);</li>
     *   <li>hospital admin: no admin role, at a hospital where they hold an
     *       active HOSPITAL_ADMIN assignment;</li>
     *   <li>any other registrar: PATIENT only, at a hospital where they
     *       actively hold a registrar role.</li>
     * </ul>
     *
     * @throws AccessDeniedException when no hospital could make this role set allowed
     */
    public Grant requireMayGrant(Collection<String> requestedRoles) {
        Caller caller = caller();
        if (caller.superAdmin()) {
            return new Grant(true, false, Set.of(), false, Set.of());
        }
        Set<String> roles = requestedRoles.stream()
            .filter(Objects::nonNull)
            .map(UserAccountAccess::bare)
            .collect(Collectors.toSet());
        boolean nonAdminRoles = !roles.isEmpty() && roles.stream().noneMatch(ADMIN_ROLES::contains);
        boolean patientOnly = roles.equals(Set.of(PATIENT));
        Set<UUID> adminHospitals = administeredHospitals(caller);
        Set<UUID> registrarHospitals = caller.presentsAny(REGISTRAR_ROLES)
            ? caller.hospitalsWhereHolding(REGISTRAR_ROLES)
            : Set.of();
        boolean asAdmin = nonAdminRoles && !adminHospitals.isEmpty();
        boolean asRegistrar = patientOnly && !registrarHospitals.isEmpty();
        if (!asAdmin && !asRegistrar) {
            throw new AccessDeniedException("Access denied");
        }
        return new Grant(false, asAdmin, adminHospitals, asRegistrar, registrarHospitals);
    }

    /** What {@link #requireMayGrant} allowed, waiting for the hospital the registration resolves to. */
    public static final class Grant {
        private final boolean anywhere;
        private final boolean asAdmin;
        private final Set<UUID> adminHospitals;
        private final boolean asRegistrar;
        private final Set<UUID> registrarHospitals;

        private Grant(boolean anywhere, boolean asAdmin, Set<UUID> adminHospitals,
                      boolean asRegistrar, Set<UUID> registrarHospitals) {
            this.anywhere = anywhere;
            this.asAdmin = asAdmin;
            this.adminHospitals = adminHospitals;
            this.asRegistrar = asRegistrar;
            this.registrarHospitals = registrarHospitals;
        }

        /**
         * @return {@code hospitalId}, when the grant holds there
         * @throws AccessDeniedException otherwise; a {@code null} hospital
         *                               (a global assignment) is the super-admin's only
         */
        public UUID requireAt(UUID hospitalId) {
            boolean allowed = anywhere || (hospitalId != null
                && ((asAdmin && adminHospitals.contains(hospitalId))
                    || (asRegistrar && registrarHospitals.contains(hospitalId))));
            if (!allowed) {
                throw new AccessDeniedException("Access denied");
            }
            return hospitalId;
        }
    }

    /**
     * The user directory (list and search) is for staff. Throws
     * {@link AccessDeniedException} for everyone else, patients included.
     */
    public void requireDirectoryAccess() {
        Caller caller = caller();
        boolean staff = caller.superAdmin() || caller.activeAssignments().stream()
            .map(a -> roleCode(a.getRole()))
            .anyMatch(code -> !code.isEmpty() && !PATIENT.equals(code));
        if (!staff) {
            throw new AccessDeniedException("Access denied");
        }
    }

    private Caller caller() {
        UUID id = currentUserId().orElse(null);
        boolean superAdmin = roleValidator.isSuperAdminFromJwtClaim();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> authorities = auth == null ? Set.of() : RoleExpansion.expand(auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .map(UserAccountAccess::prefixed)
                .toList())
            .stream()
            .map(UserAccountAccess::bare)
            .collect(Collectors.toSet());
        List<UserRoleHospitalAssignment> active = superAdmin || id == null
            ? List.of()
            : assignmentRepository.findByUser_IdAndActiveTrue(id);
        return new Caller(id, superAdmin, authorities, active);
    }

    /** Hospitals where the caller holds an ACTIVE hospital-admin assignment, if they present the role at all. */
    private static Set<UUID> administeredHospitals(Caller caller) {
        if (caller.id() == null || !caller.presentsAny(Set.of(HOSPITAL_ADMIN))) {
            return Set.of();
        }
        return caller.hospitalsWhereHolding(Set.of(HOSPITAL_ADMIN));
    }

    private boolean administers(Caller caller, User target, List<UserRoleHospitalAssignment> assignments) {
        Set<UUID> administered = administeredHospitals(caller);
        if (administered.isEmpty() || assignments.isEmpty() || holdsAny(target, assignments, ADMIN_ROLES)) {
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
                || !caller.presentsAny(REGISTRAR_ROLES)) {
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

    /** Any trace of these roles on the target, active or not, global role or assignment. */
    private static boolean holdsAny(User target, List<UserRoleHospitalAssignment> assignments, Set<String> bareRoles) {
        boolean viaAssignment = assignments.stream()
            .anyMatch(a -> bareRoles.contains(roleCode(a.getRole())));
        boolean viaGlobalRole = target.getUserRoles().stream()
            .anyMatch(ur -> bareRoles.contains(roleCode(ur.getRole())));
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

    /** An assignment's role and what RoleExpansion implies by it, bare. */
    private static Set<String> expandedCodes(Role role) {
        String code = roleCode(role);
        if (code.isEmpty()) {
            return Set.of();
        }
        return RoleExpansion.expand(List.of(prefixed(code))).stream()
            .map(UserAccountAccess::bare)
            .collect(Collectors.toSet());
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

    private static String prefixed(String role) {
        String upper = role.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("ROLE_") ? upper : "ROLE_" + upper;
    }
}
