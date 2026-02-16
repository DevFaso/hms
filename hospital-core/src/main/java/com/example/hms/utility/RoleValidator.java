package com.example.hms.utility;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RoleValidator {
    private static final String HOSPITAL_ADMIN_ROLE = "HOSPITAL_ADMIN";


    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    /* =========================================
       Authority helpers (JWT/global authorities)
       ========================================= */
    private Set<String> expandCodes(String base) {
        String u = base == null ? "" : base.toUpperCase();
        // Support both ROLE_* and bare forms
        return Set.of(u, u.startsWith("ROLE_") ? u.substring(5) : "ROLE_" + u);
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

    public boolean isSuperAdminFromAuth() { return hasAuthority("SUPER_ADMIN"); }
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
        return hasAnyAuthority(HOSPITAL_ADMIN_ROLE,"DOCTOR","PHYSICIAN","NURSE_PRACTITIONER","NURSE","MIDWIFE","STAFF","RECEPTIONIST","SUPER_ADMIN");
    }

    /* =========================================
       Current principal helpers
       ========================================= */
    public UUID getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object principal = auth.getPrincipal();

        // CustomUserDetails with userId is ideal
        if (principal instanceof com.example.hms.security.CustomUserDetails cud) {
            return cud.getUserId();
        }
        // Sometimes principal is a domain User
        if (principal instanceof com.example.hms.model.User u) {
            return u.getId();
        }
        // Could also be a plain String/username – resolve via repository if you want (not here)
        return null;
    }

    /** Resolve a single current hospital if there’s exactly one active assignment */
    public UUID getCurrentHospitalId() {
        UUID uid = getCurrentUserId();
        if (uid == null) return null;
        List<UserRoleHospitalAssignment> active = assignmentRepository.findByUser_IdAndActiveTrue(uid);
        return (active.size() == 1) ? active.get(0).getHospital().getId() : null;
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

    public boolean isDoctor(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, "DOCTOR"); }
    public boolean isPhysician(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, "PHYSICIAN"); }
    public boolean isNurse(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, "NURSE"); }
    public boolean isNursePractitioner(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, "NURSE_PRACTITIONER"); }
    public boolean isMidwife(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, "MIDWIFE"); }
    public boolean isHospitalAdmin(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, HOSPITAL_ADMIN_ROLE); }
    public boolean isLabScientist(UUID userId, UUID hospitalId) { return hasAnyCode(userId, hospitalId, "LAB_SCIENTIST"); }
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
    public boolean canCreatePrescription(UUID userId, UUID hospitalId) {
        return isDoctor(userId, hospitalId) || isHospitalAdmin(userId, hospitalId);
    }

    public boolean canOrderLabTests(UUID userId, UUID hospitalId) {
        return isDoctor(userId, hospitalId)
            || isPhysician(userId, hospitalId)
            || isNursePractitioner(userId, hospitalId);
    }

    public void requireLabScientistOrAdmin(UUID userId, UUID hospitalId, Locale locale, MessageSource messageSource) {
        if (!(isLabScientist(userId, hospitalId) || isHospitalAdmin(userId, hospitalId))) {
            throw new BusinessException(messageSource.getMessage("auth.lab.required", null, "Only Lab Scientist or Hospital Admin allowed", locale));
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
