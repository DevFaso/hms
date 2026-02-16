package com.example.hms.security.context;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * Request-scoped tenant context describing the organizational scope attached to the current principal.
 */
@Getter
@Builder(toBuilder = true)
public class HospitalContext {

    private final UUID principalUserId;
    private final String principalUsername;

    /** Preferred/active organization for the current request (may be {@code null}). */
    private final UUID activeOrganizationId;

    /** Preferred/active hospital for the current request (may be {@code null}). */
    private final UUID activeHospitalId;

    /** Organization identifiers the caller is authorized to access. */
    @Builder.Default
    private final Set<UUID> permittedOrganizationIds = Collections.emptySet();

    /** Hospital identifiers the caller is authorized to access. */
    @Builder.Default
    private final Set<UUID> permittedHospitalIds = Collections.emptySet();

    /** Department identifiers the caller is authorized to access (optional, may be empty). */
    @Builder.Default
    private final Set<UUID> permittedDepartmentIds = Collections.emptySet();

    /** Indicates caller has ROLE_SUPER_ADMIN privileges. */
    private final boolean superAdmin;

    /** Indicates caller has ROLE_HOSPITAL_ADMIN privileges. */
    private final boolean hospitalAdmin;

    public static HospitalContext empty() {
        return HospitalContext.builder()
            .principalUserId(null)
            .principalUsername(null)
            .activeOrganizationId(null)
            .activeHospitalId(null)
            .permittedOrganizationIds(Collections.emptySet())
            .permittedHospitalIds(Collections.emptySet())
            .permittedDepartmentIds(Collections.emptySet())
            .superAdmin(false)
            .hospitalAdmin(false)
            .build();
    }
}
