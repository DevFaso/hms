package com.example.hms.security.tenant;

import com.example.hms.enums.FacilityType;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.HospitalScopeResponses;
import com.example.hms.security.HospitalUserDetails;
import com.example.hms.security.audit.CrossTenantReadAudit;
import com.example.hms.security.auth.TenantRoleAssignment;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.context.HospitalContextRequestOverrides;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.EnumSet;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.example.hms.config.SecurityConstants.ROLE_HOSPITAL_ADMIN;
import static com.example.hms.config.SecurityConstants.ROLE_PATIENT;
import static com.example.hms.config.SecurityConstants.ROLE_SUPER_ADMIN;

/**
 * The one tenant resolver (docs/security/tenant-resolution.md §3).
 *
 * <p><b>Producer side.</b> Both auth filters build the request's
 * {@link HospitalContext} through {@link #liveContext} and {@link #withHeader},
 * so the permitted hospitals, the verified super-admin flag and the acting
 * hospital no longer depend on the auth path:
 * <ul>
 *   <li>the permitted set is the caller's LIVE active assignments that carry a
 *       hospital — never a token claim;</li>
 *   <li>a verified super-admin holds a live active SUPER_ADMIN assignment —
 *       never the authorities, never a token claim (Q4, option B);</li>
 *   <li>the acting hospital is the one the caller named ({@code X-Hospital-Id},
 *       refused with 403 when they may not name it — Q3, option A), else the
 *       only one they hold. A verified super-admin who names none is in global
 *       view; a caller holding several who names none has no hospital
 *       ({@code AMBIGUOUS}, Q2 option A); there is no "primary" or "newest"
 *       fallback.</li>
 * </ul>
 *
 * <p><b>Consumer side.</b> {@link #current()} reads that one answer and seals
 * it; a controller that received a hospital id narrows it once, before the
 * seal, with {@link #narrowTo(UUID)}. {@code RoleValidator.requireActiveHospitalId()}
 * and {@code ControllerAuthUtils.resolveHospitalScope} are thin adapters over
 * these two.
 *
 * <p>No generic helpers, per the {@code hospital-core} house rule.
 */
@Component
public class ActingScopeResolver {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String LAZY_CONTEXT_CLEANUP = ActingScopeResolver.class.getName() + ".lazyContext";

    private final TenantRoleAssignmentAccessor assignmentAccessor;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    /** Lazy: the audit writer reaches services that reach the resolver. */
    private final ObjectProvider<CrossTenantReadAudit> auditProvider;

    public ActingScopeResolver(TenantRoleAssignmentAccessor assignmentAccessor,
                               UserRoleHospitalAssignmentRepository assignmentRepository,
                               ObjectProvider<CrossTenantReadAudit> auditProvider) {
        this.assignmentAccessor = assignmentAccessor;
        this.assignmentRepository = assignmentRepository;
        this.auditProvider = auditProvider;
    }

    /* =====================================================================
       Producer side — the one live computation both filters use
       ===================================================================== */

    /**
     * The request's context from the caller's live assignments, before any
     * header: one database read (the assignment accessor), the same on both
     * auth paths.
     *
     * @param localUserId the caller's local user id; {@code null} gives the
     *                    {@code NO_LOCAL_USER} context
     * @param username    the caller's principal name, carried for logs and audit
     */
    public HospitalContext liveContext(UUID localUserId, String username) {
        if (localUserId == null) {
            return unlinkedContext(username);
        }
        return liveContext(localUserId, username, assignmentAccessor.findAssignmentsForUser(localUserId));
    }

    /**
     * {@link #liveContext(UUID, String)} over assignments already loaded.
     * Inactive rows are ignored.
     */
    public static HospitalContext liveContext(UUID localUserId, String username,
                                              List<TenantRoleAssignment> assignments) {
        Set<UUID> hospitals = new LinkedHashSet<>();
        Set<UUID> organizations = new LinkedHashSet<>();
        Set<String> roles = new LinkedHashSet<>();
        Map<UUID, UUID> hospitalOrganizations = new LinkedHashMap<>();
        Map<UUID, FacilityType> hospitalFacilityTypes = new LinkedHashMap<>();
        for (TenantRoleAssignment assignment : assignments == null ? List.<TenantRoleAssignment>of() : assignments) {
            if (assignment.active()) {
                collect(assignment, hospitals, organizations, roles, hospitalOrganizations);
                if (assignment.hospitalId() != null) {
                    // Never null at a facility (TenantRoleAssignment refuses it).
                    hospitalFacilityTypes.putIfAbsent(assignment.hospitalId(), assignment.facilityType());
                }
            }
        }
        Set<FacilityType> providerTypes = providerFacilityTypes(assignments);
        boolean superAdmin = roles.contains(ROLE_SUPER_ADMIN);

        HospitalContext.HospitalContextBuilder builder = HospitalContext.builder()
            .principalUserId(localUserId)
            .principalUsername(username)
            .permittedHospitalIds(Collections.unmodifiableSet(hospitals))
            // Organisations are carried for the tenant-lifecycle gate only; they
            // are not a read scope (Q6, option A).
            .permittedOrganizationIds(Collections.unmodifiableSet(organizations))
            .assignedRoles(Collections.unmodifiableSet(roles))
            .hospitalOrganizations(Collections.unmodifiableMap(hospitalOrganizations))
            .providerFacilityTypes(Collections.unmodifiableSet(providerTypes))
            .hospitalFacilityTypes(Collections.unmodifiableMap(hospitalFacilityTypes))
            .staffHospitalIds(Collections.unmodifiableSet(staffHospitalIds(assignments)))
            // The organisation policies and plan gating read: the acting
            // hospital's (set again when a hospital is named), else the only
            // organisation held. Not a read scope.
            .activeOrganizationId(organizations.size() == 1 ? organizations.iterator().next() : null)
            .superAdmin(superAdmin)
            .hospitalAdmin(roles.contains(ROLE_HOSPITAL_ADMIN));

        if (superAdmin) {
            // Global view until a hospital is named; an incidental clinical
            // assignment does not pin a super-admin (D2, D3).
            return builder.build();
        }
        if (roles.equals(Set.of(ROLE_PATIENT))) {
            // Patient-only: bounded by ownership, however many hospitals
            // registered them. Never AMBIGUOUS, never a guessed hospital.
            return builder.patientOwned(true).build();
        }
        if (hospitals.size() == 1) {
            UUID sole = hospitals.iterator().next();
            UUID organization = hospitalOrganizations.get(sole);
            if (organization != null) {
                builder.activeOrganizationId(organization);
            }
            return builder.activeHospitalId(sole).build();
        }
        return builder
            .scopeRefusal(hospitals.isEmpty() ? ActingScope.Reason.NO_HOSPITAL : ActingScope.Reason.AMBIGUOUS)
            .build();
    }

    /**
     * The provider facility types (PHARMACY, LABORATORY) among the active
     * assignments at a facility in a role other than PATIENT: what confines
     * the caller (provider plan section 3.3). Where they work, as
     * {@link #staffHospitalIds}: a PATIENT row is outside the one-kind rule
     * (#832) and confines nobody. Never HOSPITAL; empty for a hospital user.
     */
    private static Set<FacilityType> providerFacilityTypes(List<TenantRoleAssignment> assignments) {
        Set<FacilityType> types = EnumSet.noneOf(FacilityType.class);
        for (TenantRoleAssignment assignment : assignments == null ? List.<TenantRoleAssignment>of() : assignments) {
            if (assignment.active() && assignment.hospitalId() != null && assignment.facilityType() != null
                && assignment.facilityType().isProvider() && !ROLE_PATIENT.equals(roleCode(assignment))) {
                types.add(assignment.facilityType());
            }
        }
        return types;
    }

    /** The hospitals of the active assignments in a role other than PATIENT. */
    private static Set<UUID> staffHospitalIds(List<TenantRoleAssignment> assignments) {
        return (assignments == null ? List.<TenantRoleAssignment>of() : assignments).stream()
            .filter(assignment -> assignment.active() && assignment.hospitalId() != null
                && !ROLE_PATIENT.equals(roleCode(assignment)))
            .map(TenantRoleAssignment::hospitalId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** One active assignment's contribution to the live context. */
    private static void collect(TenantRoleAssignment assignment, Set<UUID> hospitals, Set<UUID> organizations,
                                Set<String> roles, Map<UUID, UUID> hospitalOrganizations) {
        String role = roleCode(assignment);
        if (role != null) {
            roles.add(role);
        }
        UUID organization = assignment.organizationId();
        if (assignment.hospitalId() != null && hospitals.add(assignment.hospitalId()) && organization != null) {
            hospitalOrganizations.put(assignment.hospitalId(), organization);
        }
        if (organization != null) {
            organizations.add(organization);
        }
    }

    /**
     * A Keycloak principal with no {@code appUserId}, or one that matches no
     * local account: no hospital, no super-admin, for anything hospital-scoped.
     */
    public static HospitalContext unlinkedContext(String username) {
        return HospitalContext.builder()
            .principalUsername(username)
            .scopeRefusal(ActingScope.Reason.NO_LOCAL_USER)
            .build();
    }

    /**
     * Apply {@code X-Hospital-Id} (design §3.3 step 2). A refused header is
     * classified here: {@code NO_LONGER_PERMITTED} when the caller holds an
     * inactive assignment at that hospital (one {@code exists} query, on the
     * refusal path only), {@code NOT_PERMITTED} otherwise.
     */
    public HospitalContext withHeader(HospitalContext live, HttpServletRequest request) {
        HospitalContext applied = HospitalContextRequestOverrides.applyRequestOverrides(live, request);
        if (applied.getScopeRefusal() == ActingScope.Reason.NOT_PERMITTED) {
            ActingScope.Reason reason = classifyRefusal(applied.getPrincipalUserId(), applied.getRefusedHospitalId());
            if (reason != ActingScope.Reason.NOT_PERMITTED) {
                applied = applied.toBuilder().scopeRefusal(reason).build();
            }
        }
        if (isRefusedHeader(applied) && isScopeEstablishing(request)) {
            // The client is asking which hospitals it may choose (or leaving):
            // a stale selection must not lock it out of the very call that
            // replaces it. The refusal is still recorded; the header is
            // ignored and the request runs on the live scope alone.
            auditRefusedHeader(applied);
            return live;
        }
        return applied;
    }

    /**
     * Paths a client needs in order to (re)establish its hospital scope, or to
     * leave: an {@code X-Hospital-Id} the caller may no longer use is IGNORED
     * there (and audited) instead of refused, so a revoked selection cannot
     * lock the portal out of its own recovery. Relative to the servlet
     * context ({@code /api}). Everything else refuses a refused header (403).
     */
    public static final Set<String> SCOPE_ESTABLISHING_PATHS = Set.of(
        "/auth/session/bootstrap",
        "/auth/logout",
        "/auth/token/refresh",
        "/me/assignments");

    /** True when {@code request} targets one of {@link #SCOPE_ESTABLISHING_PATHS}. */
    public static boolean isScopeEstablishing(HttpServletRequest request) {
        if (request == null || request.getRequestURI() == null) {
            return false;
        }
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return SCOPE_ESTABLISHING_PATHS.contains(path);
    }

    /**
     * True when the context carries a refused {@code X-Hospital-Id}: the
     * filters answer 403 for it before any controller runs. The two reasons
     * only ever come from an explicitly named hospital; the producers never
     * set them provisionally.
     */
    public static boolean isRefusedHeader(HospitalContext context) {
        ActingScope.Reason reason = context.getScopeRefusal();
        return reason == ActingScope.Reason.NOT_PERMITTED || reason == ActingScope.Reason.NO_LONGER_PERMITTED;
    }

    /** Write the refusal row for a refused header (deduplicated hourly by the audit writer). */
    public void auditRefusedHeader(HospitalContext context) {
        recordRefusal(context, context.getRefusedHospitalId(), context.getScopeRefusal(), ActingScope.Source.HEADER);
    }

    /**
     * THE answer to a refused {@code X-Hospital-Id} (design Q3, option A), for
     * every filter that builds a context: both context filters and the
     * confinement filter's fallback. When {@code context} carries a refused
     * header it is audited (hourly per actor, hospital and reason), the
     * security and hospital holders are cleared and the 403 is written with
     * its reason, so the portal re-reads its scope on a stale chip; the caller
     * then stops the chain (after clearing any holder of its own).
     *
     * @return {@code true} when the request was refused and answered
     */
    public boolean answerRefusedHeader(HospitalContext context, HttpServletResponse response) {
        if (!isRefusedHeader(context)) {
            return false;
        }
        auditRefusedHeader(context);
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        HospitalScopeResponses.writeRefusal(response, context.getScopeRefusal(), context.getRefusedHospitalId());
        return true;
    }

    /* =====================================================================
       Consumer side
       ===================================================================== */

    /**
     * The request's scope. Reading it seals it: from here on a
     * {@link #narrowTo(UUID)} to a different hospital is a programming error.
     */
    public ActingScope current() {
        ensureContext();
        return currentScope();
    }

    /**
     * {@link #current()} for code that cannot take the bean: entities, static
     * FHIR helpers, the few raw readers that hold no injected resolver.
     */
    public static ActingScope currentScope() {
        HospitalContextHolder.seal();
        return scopeOf(HospitalContextHolder.getContextOrEmpty());
    }

    /**
     * The hospital this request is pinned to, or {@code null} for global view
     * and every refusal. What the raw {@code getActiveHospitalId()} readers
     * meant to ask: for a super-admin in global view the raw value used to be
     * an incidental clinical assignment or a Keycloak attribute, depending on
     * how they signed in (D2). Reading it seals the scope.
     */
    public static UUID pinnedHospitalIdOrNull() {
        return currentScope() instanceof ActingScope.Pinned pinned ? pinned.hospitalId() : null;
    }

    /**
     * The scope a context describes, without sealing it. The filters and the
     * repository filter read it this way.
     */
    public static ActingScope scopeOf(HospitalContext context) {
        if (context.getScopeRefusal() != null) {
            return new ActingScope.Refused(context.getScopeRefusal());
        }
        if (context.isSuperAdmin() && !context.isHeaderOverridden()) {
            return new ActingScope.Global(context.getPrincipalUserId());
        }
        if (context.isPatientOwned() && !context.isHeaderOverridden()) {
            return new ActingScope.PatientOwned(context.getPrincipalUserId());
        }
        UUID active = context.getActiveHospitalId();
        if (active == null) {
            return new ActingScope.Refused(ActingScope.Reason.NO_HOSPITAL);
        }
        return new ActingScope.Pinned(active, sourceOf(context));
    }

    /** The hospital a context is pinned to, or {@code null}; does not seal. */
    public static UUID pinnedHospitalIdOf(HospitalContext context) {
        return scopeOf(context) instanceof ActingScope.Pinned pinned ? pinned.hospitalId() : null;
    }

    private static ActingScope.Source sourceOf(HospitalContext context) {
        if (context.isHeaderOverridden()) {
            return HospitalContextHolder.isNarrowed() ? ActingScope.Source.REQUESTED : ActingScope.Source.HEADER;
        }
        return context.getPermittedHospitalIds().size() == 1
            && context.getPermittedHospitalIds().contains(context.getActiveHospitalId())
            ? ActingScope.Source.SOLE_ASSIGNMENT
            : ActingScope.Source.DEFAULT;
    }

    /**
     * Narrow the scope to a hospital the controller received (a
     * {@code ?hospitalId=}, a body field or a path variable) — design §3.3
     * step 1 and §3.4 step 2. A verified super-admin may name any hospital;
     * anyone else, receptionists included, only one in their live permitted
     * set. A refusal is audited (hourly per actor, hospital and reason) and
     * returned; the context is left unchanged.
     *
     * <p>Once the scope has been read by another consumer, or narrowed
     * already, a narrow to a DIFFERENT hospital throws
     * {@link IllegalStateException}; naming the hospital already acted at is
     * a no-op.
     *
     * @param requestedHospitalId the hospital named; {@code null} returns {@link #current()}
     */
    public ActingScope narrowTo(UUID requestedHospitalId) {
        if (requestedHospitalId == null) {
            return current();
        }
        HospitalContext context = ensureContext();
        boolean permitted = context.isSuperAdmin() || context.getPermittedHospitalIds().contains(requestedHospitalId);
        if (!permitted) {
            ActingScope.Reason reason = classifyRefusal(context.getPrincipalUserId(), requestedHospitalId);
            recordRefusal(context, requestedHospitalId, reason, ActingScope.Source.REQUESTED);
            return new ActingScope.Refused(reason);
        }
        if (requestedHospitalId.equals(context.pinnedHospitalId())) {
            HospitalContextHolder.seal();
            return scopeOf(context);
        }
        if (HospitalContextHolder.isSealed() || HospitalContextHolder.isNarrowed()) {
            throw new IllegalStateException("The hospital scope of this request was already "
                + (HospitalContextHolder.isNarrowed() ? "narrowed" : "read")
                + " and cannot change: narrowTo must run once, before any other scope consumer");
        }
        HospitalContext narrowed = context.actingAt(requestedHospitalId);
        HospitalContextHolder.narrow(narrowed);
        return new ActingScope.Pinned(requestedHospitalId, ActingScope.Source.REQUESTED);
    }

    /**
     * The one hospital this request acts at. Global view and every refusal
     * answer 403 ({@link HospitalScopeRefusedException}): a caller that needs
     * a hospital cannot receive global view by accident, and every write
     * needs one (Q9, option A).
     */
    public UUID requirePinned() {
        ActingScope scope = current();
        return switch (scope) {
            case ActingScope.Pinned pinned -> pinned.hospitalId();
            case ActingScope.Global global -> throw new HospitalScopeRefusedException(
                "Select a hospital: this action needs one hospital and the request is in global view.");
            case ActingScope.Refused(ActingScope.Reason reason) -> throw new HospitalScopeRefusedException(
                reason, refusalMessage(reason));
            case ActingScope.PatientOwned owned -> throw HospitalScopeRefusedException.patientOwned();
        };
    }

    /**
     * A live active SUPER_ADMIN assignment, as the producers read it on this
     * request. Never the authorities collection, never a token claim.
     */
    public boolean isVerifiedSuperAdmin() {
        return ensureContext().isSuperAdmin();
    }

    /**
     * The request's context. A request both auth filters skipped but that is
     * authenticated with a local account (the WebSocket ticket handshake, an
     * integration test with the filter chain off) gets the SAME live
     * computation, once, on first use — never a second rule. Only inside an
     * HTTP request, and only for a password-path principal: a bearer token is
     * the Keycloak filter's to link (it checks the account against the name).
     * The context is cleared when the request completes.
     */
    private HospitalContext ensureContext() {
        var existing = HospitalContextHolder.getContext();
        if (existing.isPresent()) {
            return existing.get();
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof HospitalUserDetails details)
            || details.getUserId() == null
            || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return HospitalContext.empty();
        }
        HospitalContext live = withHeader(liveContext(details.getUserId(), details.getUsername()), attributes.getRequest());
        HospitalContextHolder.setContext(live);
        attributes.registerDestructionCallback(LAZY_CONTEXT_CLEANUP, HospitalContextHolder::clear,
            RequestAttributes.SCOPE_REQUEST);
        return live;
    }

    /**
     * The hospitals the repository filter reads (design §3.3): a pinned
     * super-admin's pin, otherwise the caller's live permitted hospitals —
     * never an organisation (Q6, option A). A context built by hand with only
     * an acting hospital (a worker thread) reads that one. Global view is the
     * caller's to test first ({@link HospitalContext#isGlobalView()}); for it
     * this answers the empty set.
     */
    public static Set<UUID> readableHospitalIds(HospitalContext context) {
        if (context.isSuperAdmin()) {
            UUID pinned = context.pinnedHospitalId();
            return pinned == null ? Set.of() : Set.of(pinned);
        }
        if (!context.getPermittedHospitalIds().isEmpty()) {
            return context.getPermittedHospitalIds();
        }
        UUID active = context.getScopeRefusal() == null ? context.getActiveHospitalId() : null;
        return active == null ? Set.of() : Set.of(active);
    }

    /** The user-facing sentence for a refusal. */
    public static String refusalMessage(ActingScope.Reason reason) {
        return switch (reason) {
            case NOT_PERMITTED, NO_LONGER_PERMITTED ->
                "Access Denied: You do not have an active role in the requested hospital.";
            case AMBIGUOUS ->
                "Select a hospital: you work at several and this request named none (X-Hospital-Id).";
            case NO_LOCAL_USER ->
                "Access Denied: this sign-in is not linked to a local account.";
            case NO_HOSPITAL ->
                "Hospital context required. Please select an active hospital or include X-Hospital-Id header.";
        };
    }

    /* ===================================================================== */

    private ActingScope.Reason classifyRefusal(UUID userId, UUID hospitalId) {
        if (userId != null && hospitalId != null
            && assignmentRepository.existsByUserIdAndHospitalIdAndActiveFalse(userId, hospitalId)) {
            return ActingScope.Reason.NO_LONGER_PERMITTED;
        }
        return ActingScope.Reason.NOT_PERMITTED;
    }

    private void recordRefusal(HospitalContext context, UUID hospitalId, ActingScope.Reason reason,
                               ActingScope.Source source) {
        CrossTenantReadAudit audit = auditProvider.getIfAvailable();
        if (audit != null) {
            audit.recordRefusal(context.getPrincipalUserId(), context.getPrincipalUsername(),
                hospitalId, reason, source);
        }
    }

    private static String roleCode(TenantRoleAssignment assignment) {
        String code = assignment.roleCode();
        if (code == null || code.isBlank()) {
            return null;
        }
        String upper = code.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith(ROLE_PREFIX) ? upper : ROLE_PREFIX + upper;
    }

    /** Visible for the filters' refusal body. */
    public static String reasonName(HospitalContext context) {
        return Objects.toString(context.getScopeRefusal(), null);
    }
}
