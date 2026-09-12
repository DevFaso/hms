package com.example.hms.service.support;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.security.context.HospitalContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves a patient for a chart READ, applying exactly the rule the patient
 * page itself applies.
 *
 * <p>Every chart tab used to do this instead:
 *
 * <pre>{@code
 * patientRepository.findById(patientId).orElseThrow(...)
 * }</pre>
 *
 * <p>That is the <em>tenant-scoped</em> finder, and as
 * {@link PatientRepository#findByIdUnscoped} documents, it adds
 * {@code WHERE hospital_id IN (permittedHospitalIds)} against
 * {@code Patient.hospitalId} — a column that holds the patient's <b>first</b>
 * hospital. A patient registered at hospital A and later linked to hospital B
 * therefore vanished for every caller at B, even though
 * {@code GET /patients/{id}} showed them the chart a moment earlier: that
 * endpoint resolves the patient unscoped and checks the registration table.
 * The result was a chart header that loaded beside tabs that all 404'd.
 *
 * <p>So this helper mirrors the patient page: resolve unscoped, then authorize
 * against the registration table. A caller who can open the patient can load
 * the patient's tabs — the two can no longer drift apart, because there is now
 * one implementation of the rule.
 *
 * <p>404-not-403 throughout: a caller asking about a patient their hospital has
 * no registration for learns nothing, not "exists elsewhere".
 */
@Component
@RequiredArgsConstructor
public class PatientChartAccess {

    /**
     * Resolvable message key, NOT a sentence. Passing prose here produced the
     * user-visible "[Missing translation] Patient not found with ID: ..." —
     * {@code ResourceNotFoundException}'s first argument is a message key and
     * {@code MessageUtil.resolve} falls back to {@code "[Missing translation] "
     * + key} when it cannot resolve one.
     */
    private static final String MSG_PATIENT_NOT_FOUND = "patient.notFound";

    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final RecordAccessPolicy recordAccessPolicy;

    /**
     * Resolve a patient the caller is allowed to read in {@code hospitalId}.
     *
     * @param patientId  the patient being charted
     * @param hospitalId the caller's resolved hospital scope. {@code null} is
     *                   honoured as "global" ONLY for a super-admin; for any
     *                   other principal it means scope could not be established
     *                   and the read is denied
     * @throws ResourceNotFoundException if no such patient, or the patient has
     *                                   no registration at {@code hospitalId}
     *                                   and no treatment relationship there
     *                                   either (E9 #58)
     */
    public Patient require(UUID patientId, UUID hospitalId) {
        if (patientId == null) {
            throw new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND, "<null>");
        }
        Patient patient = patientRepository.findByIdUnscoped(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND, patientId));

        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        if (hospitalId == null) {
            // Null means "no tenant filter". That is correct for a super-admin in
            // global view and WRONG for anyone else — but the two arrive here
            // indistinguishable, because ControllerAuthUtils.resolveHospitalScope
            // ends in `.orElse(null)` for an ordinary caller whose scope could not
            // be resolved. A ward nurse whose hospital did not resolve was
            // therefore handed a chart for a patient registered only at another
            // hospital: name, MRN, active encounter, attending, department.
            //
            // So null is honoured only for a principal the security layer marks a
            // super-admin. For anyone else it is a failure to establish scope, and
            // a failure to establish scope denies.
            if (!ctx.isSuperAdmin()) {
                throw new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND, patientId);
            }
            return patient;
        }
        if (registrationRepository.existsByPatientIdAndHospitalId(patient.getId(), hospitalId)) {
            return patient;
        }
        // E9 #58 — not linked by the desk (yet), but the patient may be on this
        // hospital's schedule, admitted here, or on an open order: the policy's
        // carriers open the chart for the staff treating them. Refused stays a
        // 404, indistinguishable from "no such patient".
        if (recordAccessPolicy.decide(ctx.getPrincipalUserId(), patient.getId(), hospitalId).permitted()) {
            return patient;
        }
        throw new ResourceNotFoundException(MSG_PATIENT_NOT_FOUND, patientId);
    }
}
