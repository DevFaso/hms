package com.example.hms.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;

/**
 * One definition of "the caller is the patient and nobody else" for the
 * prescription reads.
 *
 * <p>{@code GET /prescriptions/{id}} admits exactly five roles — four of
 * the clinical readers below plus {@code ROLE_PATIENT} — so on that endpoint
 * "holds ROLE_PATIENT and no clinical reader role" is the same thing as "a
 * pure patient principal". Two decisions hang off it and they must not drift
 * apart:
 * <ol>
 *   <li>the patient's copy omits the pharmacist-to-prescriber clarification
 *       exchange ({@code PrescriptionController.getById}), and</li>
 *   <li>the patient may only read <b>their own</b> prescription
 *       ({@code PrescriptionServiceImpl.getPrescriptionById}).</li>
 * </ol>
 *
 * <p>A super-admin is never patient-only here, and the set names
 * {@code ROLE_SUPER_ADMIN} outright rather than leaning on
 * {@link com.example.hms.security.RoleExpansion#SUPER_ADMIN_INHERITS}: the
 * expansion runs on the password/JWT path but
 * {@code KeycloakJwtAuthenticationConverter} maps realm roles straight to
 * authorities, so on the OIDC path a super-admin who is also a patient would
 * otherwise read as patient-only. Neither is a clinician who happens to be a
 * patient at the hospital — the clinical role wins, as it already did for the
 * redaction.
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
     * subject.
     *
     * <p>Wider than the five {@code GET /prescriptions/{id}} admits, on
     * purpose: {@code PrescriptionServiceImpl.getPrescriptionById} is also the
     * read-back of {@code /{id}/pharmacist-verify} and
     * {@code /{id}/request-clarification}, which admit
     * {@code ROLE_PHARMACY_VERIFIER} and {@code ROLE_SUPER_ADMIN}. Leaving
     * those two out would let a pharmacy verifier who is also a patient commit
     * the verification and then be told 404 by the read-back that follows it.
     */
    public static final Set<String> CLINICAL_READER_ROLES = Set.of(
        "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_PHARMACIST",
        "ROLE_PHARMACY_VERIFIER", "ROLE_SUPER_ADMIN");

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
