package com.example.hms.security;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.service.HospitalLifecycleStatusService;
import com.example.hms.service.OrganizationLifecycleStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Tenant lifecycle gate (MVP-2, hospital level MVP-c): a request whose caller
 * is attached to a SUSPENDED / ARCHIVED / PENDING_PURGE / PURGED organisation
 * or hospital is refused with 423.
 *
 * <p>Its semantics are deliberately NOT the acting hospital (design §3.6): it
 * refuses when <b>any</b> permitted hospital or organisation is blocked, so a
 * user with one suspended hospital cannot keep working at their others. It
 * runs on BOTH auth paths from the shared live computation, which closes D14:
 * a Keycloak user at a suspended hospital used to keep working.
 *
 * <p>A verified super-admin passes — they manage the blocked tenants.
 */
@Component
@RequiredArgsConstructor
public class TenantLifecycleGate {

    private final OrganizationLifecycleStatusService organizationLifecycleStatusService;
    private final HospitalLifecycleStatusService hospitalLifecycleStatusService;

    /** True when the request must be answered 423. */
    public boolean isBlocked(HospitalContext context) {
        if (context.isSuperAdmin()) {
            return false;
        }
        return isBlockedByOrganization(context) || isBlockedByHospital(context);
    }

    private boolean isBlockedByOrganization(HospitalContext context) {
        Set<UUID> permitted = context.getPermittedOrganizationIds();
        UUID active = context.getActiveOrganizationId();
        if (permitted.isEmpty() && active == null) {
            return false;
        }
        Set<UUID> blocked = organizationLifecycleStatusService.getBlockedOrganizationIds();
        return !blocked.isEmpty()
            && (containsAny(blocked, permitted) || (active != null && blocked.contains(active)));
    }

    private boolean isBlockedByHospital(HospitalContext context) {
        Set<UUID> permitted = context.getPermittedHospitalIds();
        // The acting hospital is checked too: a verified super-admin never
        // reaches here, so it is always one of the permitted set in practice,
        // but a context built by hand (tests, worker threads) may name only it.
        UUID active = context.getActiveHospitalId();
        if (permitted.isEmpty() && active == null) {
            return false;
        }
        Set<UUID> blocked = hospitalLifecycleStatusService.getBlockedHospitalIds();
        return !blocked.isEmpty()
            && (containsAny(blocked, permitted) || (active != null && blocked.contains(active)));
    }

    private static boolean containsAny(Set<UUID> haystack, Set<UUID> needles) {
        for (UUID id : needles) {
            if (haystack.contains(id)) {
                return true;
            }
        }
        return false;
    }
}
