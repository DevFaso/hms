package com.example.hms.service;

import com.example.hms.config.SecurityConstants;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;

/**
 * The one implementation of "the caller reached this read as its subject and
 * as nobody else", shared by every reader-role class.
 *
 * <p>It lived in two security-critical copies —
 * {@link EncounterReaderRoles} and {@link PrescriptionReaderRoles} — which
 * were identical save for the role set, and a fix to one would not have
 * reached the other. The role set stays with each endpoint family, because
 * that is what differs and what each mirror test pins against its
 * annotations; only the predicate is shared.
 */
public final class ReaderRolePredicates {

    private ReaderRolePredicates() {
    }

    /**
     * True when {@code auth} holds {@code ROLE_PATIENT} and none of
     * {@code nonSubjectRoles}.
     *
     * <p>A {@code null} authentication is not patient-only: every caller sits
     * behind {@code @PreAuthorize}, so an unauthenticated call cannot reach
     * it, and the guarded reads are also called from clinical paths whose
     * answer must not change.
     *
     * @param auth            the current authentication, may be {@code null}
     * @param nonSubjectRoles exactly the endpoint's {@code @PreAuthorize}
     *                        roles minus {@code ROLE_PATIENT} — the set for
     *                        the endpoint being served, never a union
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

    /**
     * True when {@code auth} holds {@code ROLE_PATIENT}, whatever else it
     * holds — matched exactly as {@link #isPatientOnly} matches it.
     *
     * <p>Owning a patient row is a fact about the account; holding
     * {@code ROLE_PATIENT} is the grant to act on it. A staff account can be
     * linked to a patient row whose portal role was never granted or has been
     * revoked, and such an account must not read that record through its
     * clinical role.
     */
    public static boolean holdsPatientRole(Authentication auth) {
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if (SecurityConstants.ROLE_PATIENT.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
