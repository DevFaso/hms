package com.example.hms.service;

import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.model.Patient;
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
 * <p>The one ownership check for every patient-subject read: the reads listed
 * on {@link PatientSubjectReaderRoles}, which had none (a patient could pass
 * any other patient's id and be served), and the encounter and prescription
 * reads, which used to carry their own copies of it
 * ({@code EncounterServiceImpl.requireEncounterReadable},
 * {@code PrescriptionServiceImpl.getPrescriptionById}). Written once so no
 * endpoint grows a copy:
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
     * an endpoint whose non-subject roles are {@code nonSubjectRoles}? For the
     * list reads, which are handed a patient id.
     *
     * <p>True for every caller who is not patient-only there — staff, staff
     * who are also patients, a super-admin, and a call with no authentication
     * at all (internal paths: every endpoint sits behind {@code @PreAuthorize}).
     * For a patient-only caller, true only when their account is linked to
     * exactly that patient row. A {@code null} subject is never theirs.
     *
     * @param nonSubjectRoles  the endpoint's set from {@link PatientSubjectReaderRoles}
     * @param subjectPatientId the patient the requested rows belong to, may be null
     */
    public boolean mayRead(Set<String> nonSubjectRoles, UUID subjectPatientId) {
        return !isPatientOnly(nonSubjectRoles) || ownsPatientId(subjectPatientId);
    }

    /**
     * The same question for a by-id read that has the row's patient in hand
     * ({@code order.getPatient()}): null-safe, and the subject's id is read
     * only once the caller is known to be patient-only.
     *
     * <p>Pass a {@code Patient} the caller already holds a reference to. Where
     * the patient sits behind a lazy association that staff reads never touch
     * (a report's order), ask {@link #isPatientOnly} first and only then
     * {@link #callerOwns}, so a clinician's read does not pay a SELECT to
     * initialise it.
     */
    public boolean mayRead(Set<String> nonSubjectRoles, Patient subject) {
        return !isPatientOnly(nonSubjectRoles) || callerOwns(subject);
    }

    /**
     * Does the current caller hold these non-subject roles' endpoint as its
     * subject and nobody else? Asked first wherever reading the subject, or a
     * patient-only rule such as "released reports only", costs something.
     */
    public boolean isPatientOnly(Set<String> nonSubjectRoles) {
        return ReaderRolePredicates.isPatientOnly(
            SecurityContextHolder.getContext().getAuthentication(), nonSubjectRoles);
    }

    /**
     * Is {@code subject} the current caller's own patient row? Null-safe: a
     * row whose patient cannot be placed, or a caller with no resolvable user
     * id, owns nothing. Only meaningful for a caller {@link #isPatientOnly}
     * has already classed as the subject.
     */
    public boolean callerOwns(Patient subject) {
        return subject != null && ownsPatientId(subject.getId());
    }

    /**
     * #754's rule for staff who are also patients: may a caller who is NOT
     * patient-only on this endpoint read this row as its patient anyway?
     * Only when they hold {@code ROLE_PATIENT} AND own the row. A link to a
     * patient record is a fact about the account, not a grant: a nurse linked
     * to a patient row whose {@code ROLE_PATIENT} was never granted, or was
     * revoked, must not read that row across hospitals through her clinical
     * role.
     *
     * <p>Ask it only on an endpoint whose annotation admits
     * {@code ROLE_PATIENT} — every set on {@link PatientSubjectReaderRoles}
     * is one — and only after the staff boundary has refused, so a clinician
     * reading inside their hospital never pays the ownership query. A caller
     * admitted this way reads as the patient: the caller serves the patient
     * copy (released reports only, no clinician-only fields).
     *
     * <p>{@code RoleExpansion.SUPER_ADMIN_INHERITS} gives every password-path
     * super-admin {@code ROLE_PATIENT}; through this they gain only rows their
     * own account owns, as {@link ReaderRolePredicates#holdsPatientRole}
     * records.
     */
    public boolean ownsAsItsPatient(Patient subject) {
        return holdsPatientRole() && callerOwns(subject);
    }

    /** {@link #ownsAsItsPatient(Patient)} for a read handed a patient id. */
    public boolean ownsAsItsPatient(UUID subjectPatientId) {
        return holdsPatientRole() && ownsPatientId(subjectPatientId);
    }

    /** Does the caller hold {@code ROLE_PATIENT} at all (patient-only or not)? */
    public boolean holdsPatientRole() {
        return ReaderRolePredicates.holdsPatientRole(SecurityContextHolder.getContext().getAuthentication());
    }

    private boolean ownsPatientId(UUID subjectPatientId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID callerUserId = authUtils.resolveUserId(auth).orElse(null);
        return callerUserId != null && subjectPatientId != null
            && patientRepository.existsByIdAndUserId(subjectPatientId, callerUserId);
    }
}
