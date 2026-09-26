package com.example.hms.service;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * "A patient caller only reads their own", for the reads that take a patient
 * id or the id of a row a patient owns.
 *
 * <p>The encounter and prescription reads carry this rule inline
 * ({@code EncounterServiceImpl.requireEncounterReadable},
 * {@code PrescriptionServiceImpl}); the reads listed on
 * {@link PatientSubjectReaderRoles} had none, so a patient could pass any other
 * patient's id and be served. This is the same test, written once so those
 * endpoints do not each grow a copy:
 * <ul>
 *   <li><b>who is a patient here</b> is {@link ReaderRolePredicates#isPatientOnly}
 *       against the endpoint's own set — never a union, and never
 *       {@code RoleValidator.isPatientOnlyFromAuth()}, whose fixed staff list
 *       knows nothing about the endpoint;</li>
 *   <li><b>who the caller is</b> is {@link ControllerAuthUtils#resolveUserId},
 *       which reads the {@code appUserId} claim of a Keycloak token as well as
 *       a {@code CustomUserDetails} — not {@code AuthService.getCurrentUserId()},
 *       which throws on a {@code JwtAuthenticationToken}, nor
 *       {@code RoleValidator.getCurrentUserId()}, which returns null on one;</li>
 *   <li><b>whether it is theirs</b> is {@link PatientRepository#existsByIdAndUserId}:
 *       no row loaded, no PHI decrypted, no 500 on a tenant left with
 *       duplicate {@code user_id} rows (the reasoning on that method).</li>
 * </ul>
 *
 * <p>The caller decides what a refusal looks like, because it must look
 * exactly like that endpoint's answer for an id that does not exist — the
 * same 404, key and argument on a by-id read; an empty list on a list read —
 * and must come before anything that could answer differently for a real id
 * and a made-up one (a hospital-scope check, a second lookup). Staff keep
 * their staff access: a caller holding any role of the endpoint's set is not
 * its subject there and is not asked.
 */
@Component
@RequiredArgsConstructor
public class PatientSubjectReadGuard {

    private final ControllerAuthUtils authUtils;
    private final PatientRepository patientRepository;

    /**
     * May the current caller go on to read {@code subjectPatientId}'s rows on
     * an endpoint whose non-subject roles are {@code nonSubjectRoles}?
     *
     * <p>True for every caller who is not patient-only there — staff, staff
     * who are also patients, a super-admin, and a call with no authentication
     * at all (internal paths: every endpoint sits behind {@code @PreAuthorize}).
     * For a patient-only caller, true only when their account is linked to
     * exactly that patient row. A {@code null} subject — a row whose patient
     * cannot be placed — is never theirs.
     *
     * @param nonSubjectRoles  the endpoint's set from {@link PatientSubjectReaderRoles}
     * @param subjectPatientId the patient the requested rows belong to, may be null
     */
    public boolean mayRead(Set<String> nonSubjectRoles, UUID subjectPatientId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!ReaderRolePredicates.isPatientOnly(auth, nonSubjectRoles)) {
            return true;
        }
        UUID callerUserId = authUtils.resolveUserId(auth).orElse(null);
        return callerUserId != null && subjectPatientId != null
            && patientRepository.existsByIdAndUserId(subjectPatientId, callerUserId);
    }

    /**
     * Does the current caller reach an endpoint with these non-subject roles
     * as its subject and nobody else? For the one read that names its subject
     * by something other than an id
     * ({@code GET /appointments/patients/username/{patientUsername}}), which
     * must compare that name with the caller's own before it looks anything up.
     */
    public boolean isPatientOnly(Set<String> nonSubjectRoles) {
        return ReaderRolePredicates.isPatientOnly(
            SecurityContextHolder.getContext().getAuthentication(), nonSubjectRoles);
    }
}
