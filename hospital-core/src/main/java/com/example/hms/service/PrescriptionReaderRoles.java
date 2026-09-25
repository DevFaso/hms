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
 * clinical reader role" is the same thing as "a pure patient principal". Two
 * decisions hang off it and they must not drift apart:
 * <ol>
 *   <li>the patient's copy omits the pharmacist-to-prescriber clarification
 *       exchange ({@code PrescriptionController.getById}), and</li>
 *   <li>the patient may only read <b>their own</b> prescription
 *       ({@code PrescriptionServiceImpl.getPrescriptionById}).</li>
 * </ol>
 *
 * <p>A clinician who happens to be a patient at the hospital is not
 * patient-only — the clinical role wins, as it already did for the redaction
 * — and neither is a super-admin. Which roles count, and why the set does not
 * simply mirror the annotation, is on the constant below.
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
     * <p>{@code ROLE_PHYSICIAN} and {@code ROLE_SURGEON} are named alongside
     * {@code ROLE_DOCTOR}, and {@code ROLE_SUPER_ADMIN} outright, because
     * {@link com.example.hms.security.RoleExpansion} runs on the password/JWT
     * path but {@code KeycloakJwtAuthenticationConverter} maps realm roles
     * straight to authorities. Without them a surgeon or a super-admin who is
     * also a patient at the hospital would read a colleague’s prescription
     * over one login and get a 404 over the other.
     *
     * <p>{@code ROLE_PHARMACY_VERIFIER} is deliberately NOT here. It is not a
     * reader of {@code GET /prescriptions/{id}} — the annotation does not
     * admit it — and exempting it would let a pharmacy verifier who is also
     * a patient read a stranger’s prescription through the patient door. The
     * write endpoints that do admit it return their read-back through
     * {@code PrescriptionService.getPrescriptionAfterWrite}, which skips the
     * ownership guard because the write was already authorised.
     */
    public static final Set<String> CLINICAL_READER_ROLES = Set.of(
        "ROLE_DOCTOR", "ROLE_PHYSICIAN", "ROLE_SURGEON",
        "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_PHARMACIST", "ROLE_SUPER_ADMIN");

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
