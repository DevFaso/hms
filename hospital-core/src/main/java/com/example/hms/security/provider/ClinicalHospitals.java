package com.example.hms.security.provider;

import com.example.hms.model.Hospital;

/**
 * The {@code requireClinicalHospital} rule (provider plan AC-11, item 4): when
 * a request names a hospital as a clinical destination and that row is a
 * provider facility (PHARMACY, LABORATORY), the caller gets the answer an
 * unknown id gets at that site. Each site keeps its own not-found exception
 * and message key, so the refusal is indistinguishable from a miss.
 *
 * <ul>
 *   <li><b>By id</b> (almost every destination): the repository does it,
 *       {@code hospitalRepository.findClinicalById(id)}, whose query keeps
 *       HOSPITAL rows only. A plain {@code findById} of a hospital is a
 *       recorded exception in {@code HospitalRepositoryCallerCoverageTest}.</li>
 *   <li><b>By name or code</b>, and for a collection the repository did not
 *       filter (an organisation's hospitals): this predicate, as
 *       {@code .filter(ClinicalHospitals::isClinical)}. The coverage test
 *       checks each such caller references it.</li>
 * </ul>
 */
public final class ClinicalHospitals {

    private ClinicalHospitals() {
    }

    /** True for a clinical hospital row; false for a provider facility or {@code null}. */
    public static boolean isClinical(Hospital hospital) {
        return hospital != null && !hospital.isProvider();
    }
}
