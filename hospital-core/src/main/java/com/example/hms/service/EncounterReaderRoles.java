package com.example.hms.service;

import com.example.hms.config.SecurityConstants;
import com.example.hms.security.RoleExpansion;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;

/**
 * One definition of "the caller is the encounter's patient and nobody else"
 * for the encounter reads, and one place the role sets those reads admit are
 * written down.
 *
 * <p>Two encounter reads admit {@code ROLE_PATIENT}, and they admit different
 * role sets around it, so there are two constants and one predicate rather
 * than one set used twice. Using a union would reopen exactly the defect a
 * union caused on {@code GET /prescriptions/{id}}: a role that is NOT admitted
 * by an endpoint must not be allowed to reclassify a caller who got in through
 * the patient door. A {@code ROLE_PATIENT} + {@code ROLE_RECEPTIONIST}
 * principal is refused {@code GET /encounters/&#123;id&#125;} as a receptionist
 * (the annotation does not admit the role) but admitted as a patient, so on
 * that endpoint they are a patient and only their own encounter is theirs to
 * read. The same principal on {@code /avs} is a front-desk reader.
 *
 * <p>Not to be confused with {@code RoleValidator.isPatientOnlyFromAuth()},
 * which answers a similar-sounding question against a fixed staff set that
 * knows nothing about either endpoint's annotation: it omits the three
 * consulting clinicians, so a radiologist who is also a patient is "patient
 * only" there and a clinical reader here. For an encounter read decision,
 * this class is the one to use.
 */
public final class EncounterReaderRoles {

    /**
     * {@code ROLE_PHYSICIAN} and {@code ROLE_SURGEON} are named alongside
     * {@code ROLE_DOCTOR} in both sets because
     * {@link com.example.hms.security.RoleExpansion} runs on the
     * password/JWT path but {@code KeycloakJwtAuthenticationConverter} maps
     * realm roles straight to authorities. Without them, a surgeon who is
     * also a patient at the hospital would read a colleague's encounter over
     * one login and get a 404 over the other.
     *
     * <p>{@code ROLE_SUPER_ADMIN} is named outright for the same reason, and
     * for a second one: {@code RoleExpansion.SUPER_ADMIN_INHERITS} grants a
     * super-admin {@code ROLE_PATIENT}, so on the password path every
     * super-admin looks like a patient unless the super-admin role itself
     * wins first.
     */
    private static final Set<String> DOCTOR_EQUIVALENTS = Set.of(
        SecurityConstants.ROLE_DOCTOR, RoleExpansion.ROLE_PHYSICIAN, SecurityConstants.ROLE_SURGEON);

    /**
     * The roles that read {@code GET /encounters/&#123;id&#125;} as a
     * clinician rather than as its subject — exactly
     * {@code EncounterController.ENCOUNTER_DETAIL_ROLES} minus
     * {@code ROLE_PATIENT}, plus the two doctor equivalents.
     *
     * <p>{@code ROLE_RECEPTIONIST} is deliberately absent: the detail read
     * does not admit it.
     */
    public static final Set<String> DETAIL_NON_SUBJECT_ROLES = union(
        DOCTOR_EQUIVALENTS,
        Set.of(SecurityConstants.ROLE_NURSE,
            SecurityConstants.ROLE_MIDWIFE,
            SecurityConstants.ROLE_RADIOLOGIST,
            SecurityConstants.ROLE_ANESTHESIOLOGIST,
            SecurityConstants.ROLE_PHYSIOTHERAPIST,
            SecurityConstants.ROLE_SUPER_ADMIN));

    /**
     * The roles that read {@code GET /encounters/&#123;id&#125;/avs} as
     * somebody other than its subject — exactly the annotation on
     * {@code EncounterController.getAfterVisitSummary} minus
     * {@code ROLE_PATIENT}, plus the two doctor equivalents.
     *
     * <p>{@code ROLE_RECEPTIONIST} IS here: the front desk completes the
     * check-out that produces the after-visit summary
     * ({@code POST /&#123;encounterId&#125;/checkout} admits the role) and
     * hands the printed copy to the patient, so at their own hospital the
     * summary is theirs to read. The three consulting clinicians are absent:
     * the AVS read does not admit them.
     */
    public static final Set<String> AVS_NON_SUBJECT_ROLES = union(
        DOCTOR_EQUIVALENTS,
        Set.of(SecurityConstants.ROLE_NURSE,
            SecurityConstants.ROLE_MIDWIFE,
            SecurityConstants.ROLE_RECEPTIONIST,
            SecurityConstants.ROLE_SUPER_ADMIN));

    private EncounterReaderRoles() {
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        java.util.HashSet<String> all = new java.util.HashSet<>(first);
        all.addAll(second);
        return Set.copyOf(all);
    }

    /**
     * True when {@code auth} holds {@code ROLE_PATIENT} and none of
     * {@code nonSubjectRoles}.
     *
     * <p>A {@code null} authentication is not patient-only: the handlers sit
     * behind {@code @PreAuthorize}, so an unauthenticated call cannot reach
     * them, and the services are also called from clinical paths whose answer
     * must not change.
     *
     * @param auth            the current authentication, may be {@code null}
     * @param nonSubjectRoles {@link #DETAIL_NON_SUBJECT_ROLES} or
     *                        {@link #AVS_NON_SUBJECT_ROLES} — the set for the
     *                        endpoint being served, never a union of both
     */
    public static boolean isPatientOnly(Authentication auth, Set<String> nonSubjectRoles) {
        if (auth == null) {
            return false;
        }
        boolean patient = false;
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String name = authority.getAuthority();
            if (nonSubjectRoles.contains(name)) {
                return false;
            }
            if (SecurityConstants.ROLE_PATIENT.equals(name)) {
                patient = true;
            }
        }
        return patient;
    }
}
