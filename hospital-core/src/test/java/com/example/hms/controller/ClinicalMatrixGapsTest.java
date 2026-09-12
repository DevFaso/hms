package com.example.hms.controller;

import com.example.hms.controller.pharmacy.DispenseController;
import com.example.hms.controller.pharmacy.MtmReviewController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E9 #69 — the three patient-safety gaps in the access matrix, read off the
 * annotations so a drift back fails the build.
 *
 * <ul>
 *   <li>Allergies are readable by every clinical role: a radiologist planning
 *       contrast, an anaesthetist planning induction and a physiotherapist
 *       planning a session could not read them.</li>
 *   <li>The pharmacist verifying a prescription (V139) reads the problem
 *       list, the vitals and the results — renal function decides doses.</li>
 *   <li>MIDWIFE has parity with NURSE on medications and consultations: every
 *       guard on those controllers that admits the nurse admits the midwife.</li>
 * </ul>
 */
class ClinicalMatrixGapsTest {

    @Test
    @DisplayName("allergies are readable by the consulting clinicians")
    void allergiesReadableByEveryClinicalRole() {
        String guard = GuardIndex.guardsOf(PatientController.class).get("GET /{id}/allergies");
        assertThat(guard).as("GET /{id}/allergies is guarded").isNotNull()
            .contains("'ROLE_RADIOLOGIST'", "'ROLE_ANESTHESIOLOGIST'", "'ROLE_PHYSIOTHERAPIST'",
                "'ROLE_DOCTOR'", "'ROLE_NURSE'", "'ROLE_MIDWIFE'", "'ROLE_PHARMACIST'")
            .doesNotContain("HOSPITAL_ADMIN");
    }

    @Test
    @DisplayName("the pharmacist reads the chart, diagnoses, vitals and results")
    void pharmacistReadsWhatVerificationNeeds() {
        Map<String, String> patient = GuardIndex.guardsOf(PatientController.class);
        assertThat(patient.get("GET /{id}")).as("GET /patients/{id}").contains("'ROLE_PHARMACIST'");
        assertThat(patient.get("GET /{id}/diagnoses")).as("diagnoses").contains("'ROLE_PHARMACIST'");

        Map<String, String> vitals = GuardIndex.guardsOf(PatientVitalSignController.class);
        assertThat(vitals.get("GET /recent")).as("recent vitals").contains("'ROLE_PHARMACIST'");
        assertThat(vitals.get("GET ")).as("vitals page").contains("'ROLE_PHARMACIST'");
        assertThat(vitals.get("POST ")).as("vitals write stays bedside").doesNotContain("PHARMACIST");

        Map<String, String> results = GuardIndex.guardsOf(LabResultController.class);
        assertThat(results.get("GET /{id}")).as("result by id").contains("'PHARMACIST'");
        assertThat(results.get("GET ")).as("result list").contains("'PHARMACIST'");

        assertThat(GuardIndex.guardsOf(PatientLabResultController.class).get("GET "))
            .as("patient results").contains("'ROLE_PHARMACIST'");
    }

    @Test
    @DisplayName("every medication or consultation guard that admits NURSE admits MIDWIFE")
    void midwifeHasParityWithNurse() {
        List<String> gaps = new ArrayList<>();
        for (Class<?> controller : List.of(PrescriptionController.class, PatientMedicationController.class,
            MedicationHistoryController.class, DispenseController.class, MtmReviewController.class,
            MedicationCatalogController.class, PharmacyRegistryController.class, ConsultationController.class)) {
            GuardIndex.guardsOf(controller).forEach((where, guard) -> {
                boolean nurse = guard.contains("'NURSE'") || guard.contains("'ROLE_NURSE'");
                boolean midwife = guard.contains("'MIDWIFE'") || guard.contains("'ROLE_MIDWIFE'");
                if (nurse && !midwife) {
                    gaps.add(controller.getSimpleName() + " " + where);
                }
            });
        }
        assertThat(gaps).as("guards admitting NURSE without MIDWIFE").isEmpty();
    }
}
