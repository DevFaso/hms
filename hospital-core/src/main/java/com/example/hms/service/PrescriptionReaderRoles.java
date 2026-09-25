package com.example.hms.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;

/**
 * One definition of "the caller is the patient and nobody else" for the
 * prescription reads.
 *
 * <p>{@code GET /prescriptions/{id}} admits four clinical roles plus
 * {@code ROLE_PATIENT}, so on that endpoint "holds ROLE_PATIENT and no
 * clinical reader role" is the same thing as "a pure patient principal" —
 * which is exactly why the set below must mirror the annotation and not
 * exceed it. Two decisions hang off it and they must not drift apart:
 * <ol>
 *   <li>the patient's copy omits the pharmacist-to-prescriber clarification
 *       exchange ({@code PrescriptionController.getById}), and</li>
 *   <li>the patient may only read <b>their own</b> prescription
 *       ({@code PrescriptionServiceImpl.getPrescriptionById}).</li>
 * </ol>
 *
 * <p>A clinician who happens to be a patient at the hospital is not
 * patient-only: the clinical role wins, as it already did for the redaction.
 * A super-admin is not patient-only either on the password path, where
 * {@link com.example.hms.security.RoleExpansion#SUPER_ADMIN_INHERITS} grants
 * {@code ROLE_DOCTOR} alongside {@code ROLE_PATIENT}. Which roles count, and
 * why the set mirrors the annotation exactly rather than reaching wider, is on
 * the constant below.
 *
 * <p>Not to be confused with {@code RoleValidator.isPatientOnlyFromAuth()},
 * which answers a similar-sounding question with a different staff set: it
 * omits {@code ROLE_PHARMACIST}, so a pharmacist who is also a patient is
 * "patient only" there and a clinical reader here. For a prescription
 * decision, this class is the one to use.
 */
public final class PrescriptionReaderRoles {

    /**
     * The roles that read a prescription as a clinician rather than as its
     * subject: exactly the non-patient roles {@code GET /prescriptions/{id}}
     * admits, no more.
     *
     * <p>Mirroring the annotation is the whole rule, and
     * {@code PrescriptionReaderRolesMatchTheAnnotationTest} fails if the two
     * ever disagree. Anything wider re-opens the door this guard closed: a
     * principal holding a role the annotation does NOT admit reaches the
     * handler only through {@code ROLE_PATIENT}, and exempting it would let it
     * read a stranger’s prescription — and see the clarification exchange —
     * on the strength of the patient role that let it in. That is the mistake
     * the {@code ROLE_PHARMACY_VERIFIER} exemption made; the write endpoints
     * that admit roles this read does not get their read-back from
     * {@code PrescriptionService.getPrescriptionAfterWrite} instead.
     *
     * <p>A consequence worth knowing, and NOT fixed here: on the OIDC path
     * {@code KeycloakJwtAuthenticationConverter} maps realm roles straight to
     * authorities, so {@link com.example.hms.security.RoleExpansion}’s
     * doctor-equivalence never runs. A surgeon or physician who is also a
     * patient therefore reads a colleague’s prescription over a password login
     * (expanded to {@code ROLE_DOCTOR}) and is refused it over SSO. That is a
     * refusal, not a grant, so it fails in the safe direction; the fix belongs
     * on the OIDC path or in the annotation, not in an exemption here.
     */
    public static final Set<String> CLINICAL_READER_ROLES = Set.of(
        "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_PHARMACIST");

    private PrescriptionReaderRoles() {
    }

    /**
     * True when {@code auth} holds {@code ROLE_PATIENT} and no clinical
     * reader role. A {@code null} authentication is not patient-only: the
     * handler sits behind {@code @PreAuthorize}, so an unauthenticated call
     * cannot reach it, and the service is also called from clinical paths
     * (clarification request/resolve) whose answer must not change.
     */
    public static boolean isPatientOnly(Authentication auth) {
        if (auth == null) {
            return false;
        }
        boolean patient = false;
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String name = authority.getAuthority();
            if (CLINICAL_READER_ROLES.contains(name)) {
                return false;
            }
            if ("ROLE_PATIENT".equals(name)) {
                patient = true;
            }
        }
        return patient;
    }
}
