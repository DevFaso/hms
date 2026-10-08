package com.example.hms.security.provider;

import com.example.hms.enums.FacilityType;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which roles may be held at which kind of facility (plan §3.2).
 *
 * <table>
 *   <caption>Role/facility compatibility</caption>
 *   <tr><th>Facility type</th><th>Roles that may be assigned there</th></tr>
 *   <tr><td>HOSPITAL</td><td>every role assignable today, except PROVIDER_ADMIN</td></tr>
 *   <tr><td>PHARMACY</td><td>PROVIDER_ADMIN, PHARMACIST</td></tr>
 *   <tr><td>LABORATORY</td><td>PROVIDER_ADMIN, LAB_TECHNICIAN, LAB_SCIENTIST, LAB_MANAGER, LAB_DIRECTOR</td></tr>
 * </table>
 *
 * <p>Enforced in three places: the service-level check
 * ({@code FacilityAssignmentGuard}, a 400 before anything is saved, on every
 * assignment entry point and on admin-register), the JPA backstop on
 * {@code UserRoleHospitalAssignment} (whatever its {@code active} flag says),
 * and the portal role picker (UX only). Roles are compared bare and
 * upper-case: {@code ROLE_PHARMACIST} and {@code pharmacist} are the same
 * role. Static, with no generics (house rule).
 */
public final class RoleFacilityCompatibility {

    public static final String PROVIDER_ADMIN = "PROVIDER_ADMIN";

    private static final Map<FacilityType, Set<String>> PROVIDER_ROLES = Map.of(
        FacilityType.PHARMACY, Set.of(PROVIDER_ADMIN, "PHARMACIST"),
        FacilityType.LABORATORY, Set.of(PROVIDER_ADMIN,
            "LAB_TECHNICIAN", "LAB_SCIENTIST", "LAB_MANAGER", "LAB_DIRECTOR"));

    private RoleFacilityCompatibility() {
    }

    /**
     * May this role be held at a facility of this type? A {@code null} type is
     * a hospital; a blank role is left to the callers' own validation.
     */
    public static boolean isCompatible(String role, FacilityType facilityType) {
        String bare = bare(role);
        if (bare.isEmpty()) {
            return true;
        }
        FacilityType type = FacilityType.orHospital(facilityType);
        if (type == FacilityType.HOSPITAL) {
            return !PROVIDER_ADMIN.equals(bare);
        }
        return PROVIDER_ROLES.getOrDefault(type, Set.of()).contains(bare);
    }

    /** The roles a provider of this type may hold, bare; empty for a hospital, which takes every other role. */
    public static Set<String> providerRoles(FacilityType facilityType) {
        return PROVIDER_ROLES.getOrDefault(FacilityType.orHospital(facilityType), Set.of());
    }

    /** {@code ROLE_PHARMACIST}, {@code pharmacist} → {@code PHARMACIST}; {@code null} → "". */
    public static String bare(String role) {
        if (role == null) {
            return "";
        }
        String upper = role.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("ROLE_") ? upper.substring("ROLE_".length()) : upper;
    }
}
