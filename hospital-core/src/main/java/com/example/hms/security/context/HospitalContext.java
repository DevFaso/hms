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

    /**
     * True when {@link #activeHospitalId} was set from an explicit
     * {@code X-Hospital-Id} request header (validated against the
     * principal's permitted scope by
     * {@link HospitalContextRequestOverrides}), false when it was
     * derived from a JWT claim ({@code primaryHospitalId}) or left
     * unset.
     *
     * <p>Why this matters: for a real super-admin, {@code activeHospitalId}
     * is populated from the JWT primary-hospital claim by default, but
     * the design treats super-admins as <b>global</b> by default — that
     * JWT-derived value must be ignored unless the request explicitly
     * scopes via {@code X-Hospital-Id}. Without this flag,
     * {@code RoleValidator.requireActiveHospitalId()} cannot tell the
     * two apart and either silently re-scopes super-admin reads to
     * their home hospital (the F1 click-card bug) or unconditionally
     * runs them unscoped (which breaks the explicit-header scoping
     * path Copilot flagged on the F1 fixup). This flag lets us honour
     * both: explicit scope wins, JWT-only is dropped.</p>
     */
    private final boolean headerOverridden;

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
            .headerOverridden(false)
            .build();
    }
}
