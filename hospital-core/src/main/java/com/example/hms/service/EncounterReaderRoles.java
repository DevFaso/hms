package com.example.hms.service;

import com.example.hms.config.SecurityConstants;
import org.springframework.security.core.Authentication;

import java.util.Set;

/**
 * One definition of "the caller is the encounter's patient and nobody else"
 * for the encounter reads, and one place the role sets those reads admit are
 * written down.
 *
 * <p>Three encounter reads go through one gate and admit three different role
 * sets, so there is a constant per endpoint and a single predicate that takes
 * one of them — never a union. A union would reopen exactly the defect one
 * caused on {@code GET /prescriptions/{id}}: a role that is NOT admitted by an
 * endpoint must not be allowed to reclassify a caller who got in through the
 * patient door. A {@code ROLE_PATIENT} + {@code ROLE_RECEPTIONIST}
 * principal is refused {@code GET /encounters/&#123;id&#125;} as a receptionist
 * (the annotation does not admit the role) but admitted as a patient, so on
 * that endpoint they are a patient and only their own encounter is theirs to
 * read. The same principal on {@code /avs} is a front-desk reader.
 *
 * <p>{@code /notes/history} admits no patient at all today, so it has no
 * subject; its set is here so that read shares the gate, and the ownership
 * test arrives automatically if {@code ROLE_PATIENT} is ever added to its
 * annotation.
 *
 * <p><b>The invariant, for every set below and any set added later: exactly
 * the endpoint's {@code @PreAuthorize} minus {@code ROLE_PATIENT}.</b>
 * Nothing added, nothing inferred. A role the annotation does not admit
 * cannot be a legitimate non-subject reader, and putting it here can only let
 * it in through the patient door. {@code EncounterReaderRolesMirrorTest} reads
 * each compiled annotation and fails on drift in either direction.
 *
 * <p>That is why {@code ROLE_PHYSICIAN} and {@code ROLE_SURGEON} are absent,
 * and the usual reason for naming them is what makes it wrong. On a set that
 * GRANTS access, naming them matters, because
 * {@link com.example.hms.security.RoleExpansion} maps them to
 * {@code ROLE_DOCTOR} on the password path and
 * {@code KeycloakJwtAuthenticationConverter} does not. These sets REMOVE
 * subject status, so naming them inverts into an escalation: none of the
 * three annotations admits a surgeon, so over Keycloak a
 * {@code ROLE_SURGEON} + {@code ROLE_PATIENT} principal passes
 * {@code @PreAuthorize} through the patient door alone — and would then be
 * reclassified here as a clinician and read every record at the hospital.
 * Over the password path the same principal already holds {@code ROLE_DOCTOR}
 * by expansion, so nothing legitimate is lost: what is lost is exactly the
 * escalation.
 *
 * <p>{@code ROLE_SUPER_ADMIN} is in all three because all three annotations
 * admit it, and it must win over the {@code ROLE_PATIENT} that
 * {@code RoleExpansion.SUPER_ADMIN_INHERITS} grants every super-admin on the
 * password path.
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
     * One encounter read endpoint, described once: the roles that read it as
     * somebody other than its subject, and whether its annotation admits
     * {@code ROLE_PATIENT} at all.
     *
     * <p>The two travel together because they qualify each other. Whether
     * owning an encounter is by itself a reason to read it depends on the
     * endpoint admitting patients; an earlier cut passed that as a bare
     * {@code true}/{@code false} literal at each service call site, where
     * {@code EncounterReaderRolesMirrorTest} could not see it. Flipping note
     * history's literal would have widened that endpoint across hospitals with
     * the suite green. Held here, the mirror test pins both halves against
     * the compiled annotation — the value the service actually uses.
     *
     * @param nonSubjectRoles exactly the endpoint's {@code @PreAuthorize}
     *                        roles minus {@code ROLE_PATIENT}
     * @param admitsPatient   whether that annotation admits
     *                        {@code ROLE_PATIENT}
     */
    public record ReadEndpoint(Set<String> nonSubjectRoles, boolean admitsPatient) {
    }

    /**
     * The roles that read {@code GET /encounters/&#123;id&#125;} as a
     * clinician rather than as its subject — exactly
     * {@code EncounterController.ENCOUNTER_DETAIL_ROLES} minus
     * {@code ROLE_PATIENT}.
     *
     * <p>{@code ROLE_RECEPTIONIST} is deliberately absent: the detail read
     * does not admit it.
     */
    public static final ReadEndpoint DETAIL = new ReadEndpoint(Set.of(
        SecurityConstants.ROLE_DOCTOR,
        SecurityConstants.ROLE_NURSE,
        SecurityConstants.ROLE_MIDWIFE,
        SecurityConstants.ROLE_RADIOLOGIST,
        SecurityConstants.ROLE_ANESTHESIOLOGIST,
        SecurityConstants.ROLE_PHYSIOTHERAPIST,
        SecurityConstants.ROLE_SUPER_ADMIN), true);

    /**
     * The roles that read {@code GET /encounters/&#123;id&#125;/avs} as
     * somebody other than its subject — exactly the annotation on
     * {@code EncounterController.getAfterVisitSummary} minus
     * {@code ROLE_PATIENT}.
     *
     * <p>{@code ROLE_RECEPTIONIST} IS here: the front desk completes the
     * check-out that produces the after-visit summary
     * ({@code POST /&#123;encounterId&#125;/checkout} admits the role) and
     * hands the printed copy to the patient, so at their own hospital the
     * summary is theirs to read. The three consulting clinicians are absent:
     * the AVS read does not admit them.
     */
    public static final ReadEndpoint AVS = new ReadEndpoint(Set.of(
        SecurityConstants.ROLE_SUPER_ADMIN,
        SecurityConstants.ROLE_DOCTOR,
        SecurityConstants.ROLE_NURSE,
        SecurityConstants.ROLE_MIDWIFE,
        SecurityConstants.ROLE_RECEPTIONIST), true);

    /**
     * The roles that read
     * {@code GET /encounters/&#123;encounterId&#125;/notes/history} — the
     * annotation on {@code EncounterController.getEncounterNoteHistory},
     * which has no {@code ROLE_PATIENT} to subtract.
     *
     * <p>That annotation admits no patient, so nothing is the subject here
     * today and the set is the whole of the readership. It exists anyway so
     * the read goes through the same gate as the other two: adding
     * {@code ROLE_PATIENT} to the annotation would then bring the ownership
     * test with it, instead of handing every patient the full note audit
     * trail — chief complaint, assessment, plan, author, timestamps — of
     * every encounter at their hospital.
     */
    public static final ReadEndpoint NOTE_HISTORY = new ReadEndpoint(Set.of(
        SecurityConstants.ROLE_DOCTOR,
        SecurityConstants.ROLE_NURSE,
        SecurityConstants.ROLE_MIDWIFE,
        SecurityConstants.ROLE_SUPER_ADMIN), false);

    private EncounterReaderRoles() {
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
     * @param nonSubjectRoles the {@link ReadEndpoint#nonSubjectRoles()} of
     *                        {@link #DETAIL}, {@link #AVS} or
     *                        {@link #NOTE_HISTORY} — the set for the endpoint
     *                        being served, never a union
     */
    public static boolean isPatientOnly(Authentication auth, Set<String> nonSubjectRoles) {
        // One implementation, shared with the prescription read.
        return ReaderRolePredicates.isPatientOnly(auth, nonSubjectRoles);
    }
}
