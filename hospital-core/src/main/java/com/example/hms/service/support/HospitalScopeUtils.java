package com.example.hms.service.support;

import com.example.hms.security.context.HospitalContext;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Utility helpers for resolving effective hospital scopes from {@link HospitalContext}.
 */
public final class HospitalScopeUtils {

    private HospitalScopeUtils() {
    }

    /**
     * Merge permitted hospitals with the active hospital, preserving insertion order and uniqueness.
     */
    public static LinkedHashSet<UUID> resolveScope(HospitalContext context) {
        LinkedHashSet<UUID> scope = new LinkedHashSet<>(context.getPermittedHospitalIds());
        UUID activeHospitalId = context.getActiveHospitalId();
        if (activeHospitalId != null) {
            scope.add(activeHospitalId);
        }
        scope.removeIf(Objects::isNull);
        return scope;
    }

    /**
     * Determine if a given hospitalId is within the current scope.
     */
    public static boolean isHospitalAccessible(HospitalContext context, UUID hospitalId) {
        if (hospitalId == null) {
            return false;
        }
        return resolveScope(context).contains(hospitalId);
    }
}
