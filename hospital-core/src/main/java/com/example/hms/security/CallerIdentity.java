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
}
