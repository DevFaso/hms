package com.example.hms.security.provider;

import com.example.hms.model.Hospital;

/**
 * The one {@code requireClinicalHospital} rule (provider plan AC-11, item 4):
 * when a request names a hospital as a clinical destination (a department, a
 * booking, a referral, an encounter, an invoice, a registration, a
 * break-the-glass declaration, a staff hospital) and that row is a provider
 * facility (PHARMACY, LABORATORY), the caller gets the answer an unknown id
 * gets at that site. Each site keeps its own not-found exception and message
 * key, so the refusal is indistinguishable from a miss:
 *
 * <pre>{@code
 * Hospital hospital = hospitalRepository.findById(id)
 *     .filter(ClinicalHospitals::isClinical)
 *     .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound", id));
 * }</pre>
 *
 * <p>Also the type filter for lists and counts built from a collection the
 * repository did not filter (an organisation's hospitals).
 * {@code RequireClinicalHospitalTest} holds every destination to it.
 */
public final class ClinicalHospitals {

    private ClinicalHospitals() {
    }

    /** True for a clinical hospital row; false for a provider facility or {@code null}. */
    public static boolean isClinical(Hospital hospital) {
        return hospital != null && !hospital.isProvider();
    }
}
