package com.example.hms.security;

import com.example.hms.config.SecurityConstants;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.context.ImpersonationContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * User decision 2026-11-14 (imaging access): a clinical act is done through a clinical role the
 * caller holds on their own, never through an administrative one.
 *
 * <p>{@link RoleExpansion#SUPER_ADMIN_INHERITS} gives every super-admin ROLE_DOCTOR and ROLE_NURSE
 * on every request, so {@code hasAnyRole('DOCTOR')} admits a super-admin who has never been a
 * doctor. {@link #holdsOwnRole} answers with the roles the super-admin rule did not add: the
 * caller's live assignments ({@link HospitalContext#getAssignedRoles()}) plus every token role
 * outside the inheritance list, with the doctor equivalence. For anyone else it is their
 * authorities as they are.
 *
 * <p>Static, so a {@code @PreAuthorize} reaches it with {@code T(...)} and no
 * {@code @WebMvcTest} slice needs a bean for it.
 */
public final class ClinicalActor {

    /** The administrative roles that may read imaging but never act on it. */
    public static final Set<String> ADMIN_ROLES = Set.of(
        SecurityConstants.ROLE_SUPER_ADMIN, SecurityConstants.ROLE_HOSPITAL_ADMIN, SecurityConstants.ROLE_ADMIN);

    /** The clinical roles any imaging act or full imaging read goes through. */
    public static final Set<String> IMAGING_CLINICAL_ROLES = Set.of(
        SecurityConstants.ROLE_DOCTOR, SecurityConstants.ROLE_NURSE, SecurityConstants.ROLE_MIDWIFE,
        SecurityConstants.ROLE_RADIOLOGIST);

    /**
     * QA D2120 review: the request attribute a refused clinical gate leaves for the 403 handler,
     * so the clinical sentence is used only when this gate is what refused. Its value is
     * {@link #REFUSED_ROLE} or {@link #REFUSED_IMPERSONATION}.
     */
    public static final String REFUSAL_ATTRIBUTE = ClinicalActor.class.getName() + ".refused";
    public static final String REFUSED_ROLE = "ROLE";
    public static final String REFUSED_IMPERSONATION = "IMPERSONATION";

    /**
     * Writes the audit row of a clinical act refused during a support session; set once at start-up
     * by {@code ClinicalActorAuditRegistrar}. Null in a test without a context: nothing is written.
     */
    private static final java.util.concurrent.atomic.AtomicReference<Consumer<String>> IMPERSONATION_AUDIT =
        new java.util.concurrent.atomic.AtomicReference<>();

    private ClinicalActor() {
    }

    /** Called by the registrar bean at start-up (and by tests). */
    public static void setImpersonationAudit(Consumer<String> audit) {
        IMPERSONATION_AUDIT.set(audit);
    }

    /**
     * Whether the caller holds one of {@code roles} (bare, {@code 'DOCTOR'}, or prefixed) on their
     * own — not only because they are a super-admin.
     */
    public static boolean holdsOwnRole(Authentication authentication, String... roles) {
        // QA D2120 review: a support session acting as a clinician never acts clinically for them.
        if (ImpersonationContextHolder.isImpersonating()) {
            markRefused(REFUSED_IMPERSONATION);
            Consumer<String> audit = IMPERSONATION_AUDIT.get();
            if (audit != null) {
                audit.accept(String.join(",", roles));
            }
            return false;
        }
        if (holdsOwnRoleQuietly(authentication, roles)) {
            return true;
        }
        if (authorities(authentication).contains(SecurityConstants.ROLE_SUPER_ADMIN)) {
            markRefused(REFUSED_ROLE);
        }
        return false;
    }

    /**
     * {@link #holdsOwnRole} for a service deciding what a caller may write (QA D2120 review: a
     * patient's clinical fields), with no refusal recorded: false during a support session.
     */
    public static boolean holdsOwnRoleQuietly(Authentication authentication, String... roles) {
        if (ImpersonationContextHolder.isImpersonating()) {
            return false;
        }
        Set<String> own = ownRoles(authentication);
        for (String role : roles) {
            if (role != null && own.contains(role.startsWith("ROLE_") ? role : "ROLE_" + role)) {
                return true;
            }
        }
        return false;
    }

    private static void markRefused(String reason) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            request.setAttribute(REFUSAL_ATTRIBUTE, reason, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** Why the clinical gate refused this request, or null when it did not. */
    public static String refusalOf(RequestAttributes request) {
        Object reason = request == null ? null : request.getAttribute(REFUSAL_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return reason instanceof String text ? text : null;
    }

    /**
     * The current caller reads imaging as an administrator only: they hold an administrative role
     * and no clinical imaging role of their own. Such a caller sees orders and released reports of
     * the hospitals they act at, nothing in progress and nothing across hospitals.
     */
    public static boolean isAdministrativeImagingReader() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Set<String> own = ownRoles(authentication);
        boolean admin = authorities(authentication).stream().anyMatch(ADMIN_ROLES::contains);
        return admin && own.stream().noneMatch(IMAGING_CLINICAL_ROLES::contains);
    }

    /**
     * Policy rule 3 (owner 2026-11-20, provisional): the caller reads imaging as a technician
     * only. They hold {@code ROLE_IMAGING_TECHNICIAN} on their own and none of the clinical imaging
     * roles: they read the worklist and the study's images of their hospital, and of the reports
     * only the released ones of exams they performed. Read from the caller's own roles, so it
     * holds during a support session too.
     */
    public static boolean isImagingTechnicianReader() {
        Set<String> own = ownRoles(SecurityContextHolder.getContext().getAuthentication());
        return own.contains(SecurityConstants.ROLE_IMAGING_TECHNICIAN)
            && own.stream().noneMatch(IMAGING_CLINICAL_ROLES::contains);
    }

    /**
     * Policy rule 3: the caller acts on an exam as a technician. They hold the technician role on
     * their own and are neither a doctor nor a radiologist, whose wider reach on a study's status
     * (scheduling, cancelling an order) would otherwise apply. A nurse or midwife who is also a
     * technician acts as the technician.
     */
    public static boolean actsOnExamAsTechnician() {
        Set<String> own = ownRoles(SecurityContextHolder.getContext().getAuthentication());
        return own.contains(SecurityConstants.ROLE_IMAGING_TECHNICIAN)
            && !own.contains(SecurityConstants.ROLE_DOCTOR)
            && !own.contains(SecurityConstants.ROLE_RADIOLOGIST);
    }

    static Set<String> ownRoles(Authentication authentication) {
        List<String> authorities = authorities(authentication);
        if (authorities.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> assigned = HospitalContextHolder.getContext()
            .map(ClinicalActor::rolesWhereTheRequestActs)
            .orElse(Collections.emptySet());
        // verifiedSuperAdmin=false: reconcile then drops every role only the super-admin rule gave.
        return RoleExpansion.reconcile(authorities, false, assigned);
    }

    /**
     * QA D2120 review: the assignments that count are those at the hospital the request acts at
     * (plus any bound to no hospital): a super-admin who is a doctor at A acts as a doctor at A
     * only, never at B. A context built without per-hospital roles (an older producer, a test)
     * falls back to every live assignment.
     */
    static Set<String> rolesWhereTheRequestActs(HospitalContext context) {
        boolean perHospital = context.getHospitalRoles() != null && !context.getHospitalRoles().isEmpty();
        boolean global = context.getGlobalRoles() != null && !context.getGlobalRoles().isEmpty();
        if (!perHospital && !global) {
            return context.getAssignedRoles() == null ? Set.of() : context.getAssignedRoles();
        }
        Set<String> roles = new LinkedHashSet<>(global ? context.getGlobalRoles() : Set.of());
        java.util.UUID acting = com.example.hms.security.tenant.ActingScopeResolver.pinnedHospitalIdOf(context);
        if (acting != null && perHospital) {
            roles.addAll(context.getHospitalRoles().getOrDefault(acting, Set.of()));
        }
        return roles;
    }

    private static List<String> authorities(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return List.of();
        }
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(Objects::nonNull)
            .toList();
    }
}
