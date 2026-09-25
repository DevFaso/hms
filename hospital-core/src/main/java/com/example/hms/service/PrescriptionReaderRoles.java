package com.example.hms.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

/**
 * One definition of "the caller is the patient and nobody else" for the
 * prescription reads.
 *
 * <p>{@code GET /prescriptions/{id}} admits exactly five roles — the four
 * clinical readers below plus {@code ROLE_PATIENT} — so on that endpoint
 * "holds ROLE_PATIENT and none of the four" is the same thing as "a pure
 * patient principal". Two decisions hang off it and they must not drift
 * apart:
 * <ol>
 *   <li>the patient's copy omits the pharmacist-to-prescriber clarification
 *       exchange ({@code PrescriptionController.getById}), and</li>
 *   <li>the patient may only read <b>their own</b> prescription
 *       ({@code PrescriptionServiceImpl.getPrescriptionById}).</li>
 * </ol>
 *
 * <p>A super-admin is never patient-only here even though
 * {@link com.example.hms.security.RoleExpansion#SUPER_ADMIN_INHERITS} grants
 * {@code ROLE_PATIENT}: the same expansion grants {@code ROLE_DOCTOR}, which
 * is in the clinical set. Neither is a clinician who also happens to be a
 * patient at the hospital — the clinical role wins, as it already did for the
 * redaction.
 */
public final class PrescriptionReaderRoles {

    /** The reader roles of {@code GET /prescriptions/{id}} that are not the patient. */
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

    /** The same rule against the authentication on the current thread. */
    public static boolean isPatientOnlyPrincipal() {
        return isPatientOnly(SecurityContextHolder.getContext().getAuthentication());
    }
}
