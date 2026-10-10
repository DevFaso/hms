package com.example.hms.security.context;

import com.example.hms.enums.FacilityType;
import com.example.hms.security.tenant.ActingScope;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;
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

    /**
     * The caller is a <b>verified</b> super-admin: they hold a live, active
     * SUPER_ADMIN assignment, read from the assignment table on this request
     * by {@code ActingScopeResolver.liveContext} on both auth paths. Never the
     * token's claim, never the authorities collection (design Q4, option B).
     */
    private final boolean superAdmin;

    /** Indicates caller has ROLE_HOSPITAL_ADMIN privileges. */
    private final boolean hospitalAdmin;

    /**
     * True when {@link #activeHospitalId} was named <b>explicitly</b> by the
     * caller: the {@code X-Hospital-Id} header (validated against the live
     * permitted set by {@link HospitalContextRequestOverrides}), or a hospital
     * the controller narrowed to with {@code ActingScopeResolver.narrowTo}.
     * False when it came from the caller holding exactly one hospital, or is
     * unset. (The name predates {@code narrowTo}; design §3.4 calls it
     * "explicit".)
     *
     * <p>It is what pins a super-admin: without an explicit hospital a
     * verified super-admin is in global view, with one they act at it.
     */
    private final boolean headerOverridden;

    /**
     * Why this request has no hospital to act at, or {@code null} when it has
     * one (or is a super-admin in global view). Set by the producers for a
     * caller holding several hospitals who named none ({@code AMBIGUOUS}), one
     * holding none ({@code NO_HOSPITAL}), a Keycloak principal with no local
     * account ({@code NO_LOCAL_USER}), and an {@code X-Hospital-Id} the caller
     * may not use ({@code NOT_PERMITTED} / {@code NO_LONGER_PERMITTED}; the
     * filters answer 403 for that one before any controller runs).
     */
    private final ActingScope.Reason scopeRefusal;

    /** The hospital an explicit refusal named ({@code X-Hospital-Id}), for the refusal audit. */
    private final UUID refusedHospitalId;

    /**
     * The role codes ({@code ROLE_*}) of the caller's live active assignments.
     * Read only to reconcile the authorities collection with the verified
     * super-admin signal (design Q10, option A); never a scope input.
     */
    @Builder.Default
    private final Set<String> assignedRoles = Collections.emptySet();

    /**
     * The caller holds ROLE_PATIENT and nothing else: their requests are
     * bounded by ownership of their own records, not by a hospital (design
     * Q1), so no hospital is pinned for them unless they name one.
     */
    private final boolean patientOwned;

    /**
     * The organisation of each hospital the caller holds, from the same live
     * assignments. Read only to keep {@link #activeOrganizationId} the
     * organisation of the hospital the request acts at (organisation policies,
     * plan gating); never a read scope (design Q6, option A).
     */
    @Builder.Default
    private final Map<UUID, UUID> hospitalOrganizations = Collections.emptyMap();

    /**
     * The provider facility types (PHARMACY, LABORATORY; never HOSPITAL) among
     * the caller's live active assignments, from the same read as the
     * permitted set. Non-empty means the caller is confined to the provider
     * allow-list, unless a verified super-admin (provider plan section 3.3).
     */
    @Builder.Default
    private final Set<FacilityType> providerFacilityTypes = Collections.emptySet();

    /**
     * The facility type of each hospital in {@link #permittedHospitalIds},
     * from the same assignment read: a decision about the acting hospital's
     * kind (a provider facility reads no chart, AC-9) costs no lookup for
     * one of the caller's own. A facility missing from it (a context built
     * by hand, a super-admin or anyone naming another facility) is looked up.
     */
    @Builder.Default
    private final Map<UUID, FacilityType> hospitalFacilityTypes = Collections.emptyMap();

    /**
     * The hospitals where the caller holds a live active assignment in a role
     * other than PATIENT: where they work, not where they are treated. A
     * hospital-bound PATIENT row puts its hospital in {@link #permittedHospitalIds}
     * but not here.
     */
    @Builder.Default
    private final Set<UUID> staffHospitalIds = Collections.emptySet();

    /**
     * This context acting at {@code hospitalId}, named explicitly: the active
     * organisation follows the hospital (null when the caller holds no
     * assignment there, e.g. a super-admin naming another tenant).
     */
    public HospitalContext actingAt(UUID hospitalId) {
        return toBuilder()
            .activeHospitalId(hospitalId)
            .activeOrganizationId(hospitalId == null ? null : hospitalOrganizations.get(hospitalId))
            .headerOverridden(true)
            .scopeRefusal(null)
            .refusedHospitalId(null)
            .build();
    }

    /**
     * The hospital this request is pinned to, or {@code null} when it is not
     * pinned. A super-admin is global unless an explicit hospital was named
     * ({@link #headerOverridden}); everyone else is pinned to their
     * {@link #activeHospitalId}, which the producers derive from the LIVE
     * assignment table on every request: the hospital the caller named, else
     * the only one they hold. A refused scope pins nothing.
     *
     * <p>Callers outside {@code security/**} ask
     * {@code ActingScopeResolver} instead; this accessor is what the resolver,
     * the repository filter and the FHIR boundary read.
     */
    public UUID pinnedHospitalId() {
        if (((superAdmin || patientOwned) && !headerOverridden) || scopeRefusal != null) {
            return null;
        }
        return activeHospitalId;
    }

    /** A verified super-admin who named no hospital: reads span every tenant. */
    public boolean isGlobalView() {
        return superAdmin && !headerOverridden && scopeRefusal == null;
    }

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
