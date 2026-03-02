package com.example.hms.service;

import com.example.hms.enums.ShareScope;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientConsent;

import java.util.UUID;

/**
 * Resolves the best available consent for sharing a patient's records with a
 * requesting hospital, using a tiered search strategy:
 *
 * <ol>
 *   <li><b>SAME_HOSPITAL</b> — The requesting hospital is already a registered source;
 *       the patient's data is already local to that hospital. No consent needed.</li>
 *   <li><b>INTRA_ORG</b>     — Both hospitals share the same Organisation.
 *       Any active consent from <em>any</em> hospital in that organisation to the
 *       requesting hospital is accepted.</li>
 *   <li><b>CROSS_ORG</b>     — The hospitals are in different organisations.
 *       An explicit bilateral consent record is required.</li>
 * </ol>
 */
public interface ConsentResolutionService {

    /**
     * Resolve the best consent for the given patient and requesting hospital.
     *
     * @param patientId            the patient whose records are requested
     * @param requestingHospitalId the hospital that wants to read the records
     * @return a populated {@link ConsentContext} — never null
     * @throws com.example.hms.exception.BusinessException     if no active consent is found at any tier
     * @throws com.example.hms.exception.ResourceNotFoundException if patient or hospital is not found
     */
    ConsentContext resolve(UUID patientId, UUID requestingHospitalId);

    /**
     * Lightweight value object that carries the outcome of the resolution.
     *
     * @param scope              which tier was used to satisfy the request
     * @param sourceHospital     the hospital whose records will be served
     * @param requestingHospital the hospital that initiated the request
     * @param consent            the consent record that authorises this share (null for SAME_HOSPITAL)
     * @param patient            the resolved patient entity
     */
    record ConsentContext(
        ShareScope scope,
        Hospital sourceHospital,
        Hospital requestingHospital,
        PatientConsent consent,
        Patient patient
    ) {
        /** True when the requesting hospital IS the source — no consent needed. */
        public boolean isSelfServe() {
            return scope == ShareScope.SAME_HOSPITAL;
        }
    }
}
