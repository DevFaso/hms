package com.example.hms.utility;

import com.example.hms.config.SecurityConstants;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.PrincipalUserIds;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RoleValidator {
    private static final String HOSPITAL_ADMIN_ROLE = "HOSPITAL_ADMIN";
    private static final String DOCTOR_ROLE = "DOCTOR";
    private static final String PHYSICIAN_ROLE = "PHYSICIAN";
    private static final String SURGEON_ROLE = "SURGEON";
    private static final String NURSE_ROLE = "NURSE";
    private static final String ROLE_PREFIX = SecurityConstants.ROLE_PREFIX;

    /**
     * What a caller with no resolvable hospital is told. Public so a guard
     * that refuses the same condition for its own reason — the encounter
     * reads refuse a {@code null} scope the verified super-admin flag does not
     * back — says it in the same words, and a rewording here reaches both.
     */
    public static final String HOSPITAL_CONTEXT_REQUIRED =
        "Hospital context required. Please select an active hospital or include X-Hospital-Id header.";


    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final ActingScopeResolver actingScopeResolver;

    /* =========================================
       Authority helpers (JWT/global authorities)
       ========================================= */
    private Set<String> expandCodes(String base) {
        String u = base == null ? "" : base.toUpperCase();
        // Support both ROLE_* and bare forms
        return Set.of(u, u.startsWith(ROLE_PREFIX) ? u.substring(ROLE_PREFIX.length()) : ROLE_PREFIX + u);
    }

    public boolean hasAnyAuthority(String... bases) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        var wanted = new HashSet<String>();
        for (var b : bases) wanted.addAll(expandCodes(b));
        return auth.getAuthorities().stream()
            .map(a -> a.getAuthority().toUpperCase())
            .anyMatch(wanted::contains);
    }

    private boolean hasAuthority(String base) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        var wanted = expandCodes(base);
        return auth.getAuthorities().stream()
            .map(a -> a.getAuthority().toUpperCase())
            .anyMatch(wanted::contains);
    }

    /**
     * The same answer as {@link #isSuperAdminFromJwtClaim()}: the live,
     * verified signal ({@link ActingScopeResolver#isVerifiedSuperAdmin()}).
     * It used to read the authorities collection, which a stale token, a
     * demoted super-admin or a Keycloak realm role could carry; its callers
     * now agree with every other scope decision, so it is kept as a correct
     * adapter for them rather than deprecated.
     */
    public boolean isSuperAdminFromAuth() { return isSuperAdminFromJwtClaim(); }

    /**
     * The verified super-admin signal for scope decisions: the caller holds a
     * live, active SUPER_ADMIN assignment, read from the assignment table on
     * this request by the shared computation both auth filters use
     * ({@link ActingScopeResolver#liveContext}). It is NOT the token's
     * {@code isSuperAdmin} claim and NOT the authorities collection — the name
     * is historical: the claim it once read was itself derived from the
     * authorities, and on Keycloak the authority was the only input (design
     * D5). A demoted super-admin loses it on the next request.
     */
    public boolean isSuperAdminFromJwtClaim() {
        return actingScopeResolver.isVerifiedSuperAdmin();
    }

    public boolean isHospitalAdminFromAuthGlobalOnly() { return hasAuthority(HOSPITAL_ADMIN_ROLE); }
    public boolean isPatientFromAuth() { return hasAuthority("PATIENT"); }

    /** True iff the caller has PATIENT role and does NOT have any staff/admin role */
    public boolean isPatientOnlyFromAuth() {
        boolean isPatient = hasAnyAuthority("PATIENT");
        boolean isStaffOrAdmin = isStaffOrAdminFromAuth();
        return isPatient && !isStaffOrAdmin;
    }

    /** Quick check for “can act as staff/admin” */
    public boolean isStaffOrAdminFromAuth() {
        return hasAnyAuthority(HOSPITAL_ADMIN_ROLE, DOCTOR_ROLE, PHYSICIAN_ROLE, NURSE_ROLE,"MIDWIFE","STAFF","RECEPTIONIST","SUPER_ADMIN");
    }

    /* =========================================
       Current principal helpers
       ========================================= */
    /**
     * The caller's local user id: a password-path principal's, or a Keycloak
     * principal's {@code appUserId} claim ({@link PrincipalUserIds}). Null
     * when there is none.
     */
    public UUID getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.example.hms.model.User u) {
            return u.getId();
        }
        return PrincipalUserIds.of(auth).orElse(null);
    }

    /**
     * The hospital this request acts at, or {@code null} for global view or no
     * hospital. Once its own "exactly one active assignment" lookup; now the
     * resolver's answer, whose {@code SOLE_ASSIGNMENT} rule subsumes it.
     */
    public UUID getCurrentHospitalId() {
        return actingScopeResolver.current() instanceof ActingScope.Pinned pinned ? pinned.hospitalId() : null;
    }

    /**
     * The hospital this request acts at, from the one tenant resolver
     * ({@link ActingScopeResolver}, docs/security/tenant-resolution.md). This
     * is the thin adapter every hospital-scoped service calls:
     * <ul>
     *   <li>{@code Pinned} → its hospital: the one the caller named
     *       ({@code X-Hospital-Id} or a controller's {@code narrowTo}), else
     *       the only one they hold;</li>
     *   <li>{@code Global} → {@code null}: a VERIFIED super-admin (a live
     *       SUPER_ADMIN assignment) who named no hospital. Nothing else can
     *       reach {@code null}; the old authorities-based fallback ("step 4")
     *       is gone;</li>
     *   <li>{@code Refused} (several hospitals and none named, none held, no
     *       local account) → {@link BusinessException} with
     *       {@link #HOSPITAL_CONTEXT_REQUIRED}.</li>
     * </ul>
     * Reading the scope seals it for the rest of the request.
     */
    public UUID requireActiveHospitalId() {
        ActingScope scope = actingScopeResolver.current();
        return switch (scope) {
            case ActingScope.Pinned pinned -> pinned.hospitalId();
            case ActingScope.Global global -> null;
            case ActingScope.Refused refused -> throw new BusinessException(HOSPITAL_CONTEXT_REQUIRED);
            // Never null for a patient: null means an unscoped super-admin to
            // every caller of this method. A patient-reached path takes its
            // hospital from the record (design Q1).
            case ActingScope.PatientOwned owned -> throw HospitalScopeRefusedException.patientOwned();
        };
    }

    /** Active assignment for (currentUser, currentHospital) if uniquely determined */
    public UserRoleHospitalAssignment getCurrentAssignmentForHospital() {
        UUID uid = getCurrentUserId();
        UUID hid = getCurrentHospitalId();
        if (uid == null || hid == null) return null;
        return assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(uid, hid).orElse(null);
    }

    /* =========================================
       Hospital‑scoped role checks (DB-backed)
       ========================================= */
    private Set<String> expandCodesForDb(String base) {
        // Reuse expandCodes but DB layer may accept both forms
        return expandCodes(base);
    }

    private boolean hasAnyCode(UUID userId, UUID hospitalId, String baseCode) {
        return assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
            userId, hospitalId, expandCodesForDb(baseCode));
    }

    public boolean isDoctor(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, DOCTOR_ROLE); }
    public boolean isPhysician(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, PHYSICIAN_ROLE); }
    public boolean isSurgeon(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, SURGEON_ROLE); }
    public boolean isNurse(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, NURSE_ROLE); }
    public boolean isMidwife(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, "MIDWIFE"); }
    public boolean isHospitalAdmin(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE); }
    public boolean isLabScientist(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, "LAB_SCIENTIST"); }
    public boolean isLabTechnician(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, "LAB_TECHNICIAN"); }
    public boolean isLabManager(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, "LAB_MANAGER"); }
    /** True if the user has any lab staff role (scientist, technician, or manager) in the given hospital. */
    public boolean isLabStaff(UUID userId, UUID hospitalId) {
        return isLabScientist(userId, hospitalId)
            || isLabTechnician(userId, hospitalId)
            || isLabManager(userId, hospitalId);
    }
    public boolean hasRole(UUID userId, UUID hospitalId, String roleCode) { return hasAnyCode(userId, hospitalId, roleCode); }

    public void validateRoleOrThrow(UUID userId, UUID hospitalId, String roleCode, Locale locale, MessageSource messageSource) {
        if (!hasAnyCode(userId, hospitalId, roleCode)) {
            throw new BusinessException(
                messageSource.getMessage("role.notfound", new Object[]{userId, hospitalId}, "Role not found", locale));
        }
    }

    public boolean isAnyActiveRole(UUID userId) { return assignmentRepository.existsByUserIdAndActiveTrue(userId); }

    /* =========================================
       Convenience
       ========================================= */
    /**
     * Prescribing is a clinical act: a hospital admin no longer passes (E9 #67,
     * D5), and midwives, who prescribe throughout the OB module, have parity
     * with nurses (E9 #69).
     *
     * <p>Physician and surgeon are named because {@code RoleExpansion}'s
     * doctor-equivalence rule — a surgeon and a physician ARE doctors — runs on
     * the authorities, so {@code hasAnyAuthority('ROLE_DOCTOR',...)} on
     * {@code POST /prescriptions} already admits them, while the checks in this
     * class match the stored ASSIGNMENT code and do not know that. Without the
     * two arms the endpoint let them in and this predicate threw them out with
     * {@code prescription.only.doctor.admin}.
     */
    public boolean canCreatePrescription(UUID userId, UUID hospitalId) {
        return isDoctor(userId, hospitalId)
            || isPhysician(userId, hospitalId)
            || isSurgeon(userId, hospitalId)
            || isNurse(userId, hospitalId)
            || isMidwife(userId, hospitalId);
    }

    /**
     * Surgeon for the same reason as above: {@code POST /lab-orders} admits
     * {@code ROLE_DOCTOR}, which a surgeon holds by expansion, and the order
     * was then refused here. Midwife is deliberately NOT added — the endpoint
     * admits it but nothing has established that a midwife orders lab tests,
     * and widening that is not this change's call.
     */
    public boolean canOrderLabTests(UUID userId, UUID hospitalId) {
        return LAB_ORDERING_ROLE_CODES.stream().anyMatch(code -> hasAnyCode(userId, hospitalId, code));
    }

    /** The role codes {@link #canOrderLabTests} admits, in the order it checks them. */
    private static final java.util.List<String> LAB_ORDERING_ROLE_CODES =
        java.util.List.of(DOCTOR_ROLE, PHYSICIAN_ROLE, SURGEON_ROLE, NURSE_ROLE);

    /**
     * Whether this one assignment's role is one {@link #canOrderLabTests}
     * admits - the same codes, matched the way the query matches them
     * ({@code UPPER(role.code)}, bare or {@code ROLE_}-prefixed). Says nothing
     * about whether the assignment is active or where it is.
     */
    public static boolean isLabOrderingRole(UserRoleHospitalAssignment assignment) {
        String code = assignment == null || assignment.getRole() == null ? null : assignment.getRole().getCode();
        if (code == null) {
            return false;
        }
        String upper = code.toUpperCase(Locale.ROOT);
        String bare = upper.startsWith(ROLE_PREFIX) ? upper.substring(ROLE_PREFIX.length()) : upper;
        return LAB_ORDERING_ROLE_CODES.contains(bare);
    }

    public void requireLabScientistOrAdmin(UUID userId, UUID hospitalId, Locale locale, MessageSource messageSource) {
        if (!(isLabScientist(userId, hospitalId) || isHospitalAdmin(userId, hospitalId))) {
            throw new BusinessException(messageSource.getMessage("auth.lab.required", null, "Only Lab Scientist or Hospital Admin allowed", locale));
        }
    }

    public void requireLabStaffOrAdmin(UUID userId, UUID hospitalId, Locale locale, MessageSource messageSource) {
        if (!(isLabStaff(userId, hospitalId) || isHospitalAdmin(userId, hospitalId))) {
            throw new BusinessException(messageSource.getMessage("auth.lab.staff.required", null, "Only Lab staff or Hospital Admin allowed", locale));
        }
    }

    public boolean canLinkInsurance(UUID actorUserId, UUID hospitalId) {
        // Staff can link insurance if they have any role in the hospital
        if (isStaffOrAdminFromAuth()) {
            return assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(actorUserId, hospitalId);
        }
        // Patients can only link their own insurance
        return actorUserId != null && actorUserId.equals(getCurrentUserId());
    }
    public boolean canViewPatient(UUID actorUserId, UUID patientId) {
        // Staff can view any patient in their hospital
        if (isStaffOrAdminFromAuth()) {
            return assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(actorUserId, getCurrentHospitalId());
        }
        // Patients can only view themselves
        return actorUserId != null && actorUserId.equals(patientId);
    }

}
