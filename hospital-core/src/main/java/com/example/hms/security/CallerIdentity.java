package com.example.hms.security;

import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.model.Staff;
import com.example.hms.utility.MessageUtil;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * QA D302/D406/D407/D503 class: an identity the server should derive (the
 * signer, the author, the provider, the nurse who asked) is never taken from
 * the request. A staff id in a body or a query string is a claim; the service
 * resolves it, then asks this class whether it is the caller's own record.
 *
 * <p>Static, so a service adopts it without a new constructor dependency.
 * {@code ClientSuppliedIdentityGuardTest} lists every write endpoint that
 * still accepts such an id and why.
 */
public final class CallerIdentity {

    private CallerIdentity() {
    }

    /** The caller's local user id, or {@code null} when there is none. */
    public static UUID currentUserId() {
        org.springframework.security.core.Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.example.hms.model.User user) {
            return user.getId();
        }
        return PrincipalUserIds.of(auth).orElse(null);
    }

    /** True when {@code staff} is the caller's own staff record. */
    public static boolean isCaller(Staff staff) {
        UUID caller = currentUserId();
        return caller != null && staff != null && staff.getUser() != null
            && caller.equals(staff.getUser().getId());
    }

    /**
     * Refuse (403, with a message about the caller) unless {@code staff} is the
     * caller's own staff record. The record must already have been resolved in
     * the caller's scope, so the refusal discloses nothing.
     */
    public static void requireCaller(Staff staff) {
        if (!isCaller(staff)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("identity.notCaller"));
        }
    }

    /**
     * {@link #requireCaller(Staff)}, except for a verified super-admin in
     * global view (the one resolver's {@code Global} scope), who may act for a
     * staff member already checked to belong to the hospital in question.
     */
    public static void requireCallerUnlessSuperAdmin(Staff staff) {
        if (!isGlobalSuperAdmin()) {
            requireCaller(staff);
        }
    }

    /** True for a verified super-admin in global view, as the one resolver decides it. */
    public static boolean isGlobalSuperAdmin() {
        return com.example.hms.security.tenant.ActingScopeResolver.currentScope()
            instanceof com.example.hms.security.tenant.ActingScope.Global;
    }

    /**
     * The acting staff member for a write that records who did it (the
     * recorder, the performer, the author, the canceller). A claimed staff id
     * must be a staff record at {@code hospitalId} and, unless the caller is a
     * super-admin in global view, the caller's own; with no claim the caller's
     * own staff record at that hospital is used (or {@code null} when the
     * caller has none there). QA D503 class.
     */
    public static Staff resolveActor(com.example.hms.repository.StaffRepository staffRepository,
                                     UUID claimedStaffId, UUID hospitalId) {
        if (claimedStaffId == null) {
            UUID caller = currentUserId();
            if (caller == null || hospitalId == null) {
                return null;
            }
            return staffRepository.findByUserIdAndHospitalId(caller, hospitalId).orElse(null);
        }
        Staff claimed = staffRepository.findById(claimedStaffId)
            .orElseThrow(() -> new com.example.hms.exception.ResourceNotFoundException("staff.notFound", claimedStaffId));
        return confirmActor(staffRepository, claimed, hospitalId);
    }

    /**
     * Confirm an already-loaded claimed actor: the caller's own record at
     * {@code hospitalId} (a caller who sent their staff record at another
     * hospital gets their record at this one), or, for a super-admin in global
     * view, any staff record at that hospital.
     */
    public static Staff confirmActor(com.example.hms.repository.StaffRepository staffRepository,
                                     Staff claimed, UUID hospitalId) {
        if (isCaller(claimed) && hospitalId != null && (claimed.getHospital() == null
            || !hospitalId.equals(claimed.getHospital().getId()))) {
            return staffRepository.findByUserIdAndHospitalId(currentUserId(), hospitalId)
                .orElseThrow(() -> new com.example.hms.exception.ResourceNotFoundException("staff.notFound", claimed.getId()));
        }
        requireAtHospital(claimed, hospitalId);
        requireCallerUnlessSuperAdmin(claimed);
        return claimed;
    }

    /** The id of a record's hospital, or {@code null} when the record has none. */
    public static UUID hospitalIdOf(com.example.hms.model.Hospital hospital) {
        return hospital == null ? null : hospital.getId();
    }

    /** Refuse a staff record that is not at {@code hospitalId} (a null hospital is never a match). */
    public static void requireAtHospital(Staff staff, UUID hospitalId) {
        if (hospitalId != null && (staff == null || staff.getHospital() == null
            || !hospitalId.equals(staff.getHospital().getId()))) {
            throw new com.example.hms.exception.ResourceNotFoundException("staff.notFound",
                staff == null ? null : staff.getId());
        }
    }

    /**
     * A user id the client names as the one who performed something must be the
     * caller's (super-admin in global view excepted); with none, the caller's.
     */
    public static UUID resolveActorUserId(UUID claimedUserId) {
        UUID caller = currentUserId();
        if (claimedUserId == null) {
            return caller;
        }
        if (!claimedUserId.equals(caller) && !isGlobalSuperAdmin()) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("identity.notCaller"));
        }
        return claimedUserId;
    }
}
