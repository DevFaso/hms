package com.example.hms.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static com.example.hms.controller.GuardIndex.assertKept;
import static com.example.hms.controller.GuardIndex.assertOffChart;
import static com.example.hms.controller.GuardIndex.assertRefuses;

/**
 * E9 #67, decision D5 — HOSPITAL_ADMIN is administrative-only on the chart.
 *
 * <p>Slice (b1): the patient chart page and what it opens onto — the
 * {@code /patients/{id}} clinical sub-resources, the storyboard, chart
 * review, vitals, encounters, nursing notes, admissions, discharge,
 * transfers and isolation, consultations, referrals, the in-basket and CDS
 * acknowledgements. A hospital admin keeps demographics, registration,
 * coverage, the order-set and smart-phrase catalogs, the ops sweeps and
 * break-the-glass; those guards are listed here as the exceptions so the
 * test fails if the cut over-reaches as well as if it drifts back.
 *
 * <p>Asserted against the annotations, like {@link NurseReadAccessTest}: the
 * annotation is the contract, and the {@code @WebMvcTest} slices do not run
 * method security; the reflection lives in {@link GuardIndex}. The matcher layer
 * in {@code SecurityConfig} is covered by {@code SecurityConfigChartMatcherTest}.
 */
@DisplayName("HOSPITAL_ADMIN off the clinical chart (E9 #67 b1)")
class HospitalAdminOffChartTest {

    @Test
    @DisplayName("the chart sub-resources of /patients refuse the role; demographics keep it")
    void patientChartSubResources() {
        for (String where : new String[] {
            "GET /{id}/allergies", "GET /{id}/diagnoses", "GET /{id}/chart-updates",
            "GET /{patientId}/chart-updates/{updateId}"}) {
            assertRefuses(PatientController.class, where);
        }
        // What a hospital admin is for: the demographic record and its registration.
        assertKept(PatientController.class, "GET /{id}");
        assertKept(PatientController.class, "PUT /{id}");
        // E8 #54: restricting a chart is the administrator's act.
        assertKept(PatientController.class, "POST /{id}/chart-restriction");
    }

    @Test
    @DisplayName("the whole-chart and per-domain reads under the chart page refuse the role")
    void chartPageSurfaces() {
        assertOffChart(PatientStoryboardController.class);
        assertOffChart(ChartReviewController.class);
        assertOffChart(PatientVitalSignController.class);
        assertOffChart(PatientLabResultController.class);
        assertOffChart(PatientMedicationController.class);
        assertOffChart(PatientMicroCultureController.class);
        assertOffChart(PatientRecordExportController.class);
        assertOffChart(GrowthChartController.class);
        assertOffChart(IntakeOutputController.class);
    }

    @Test
    @DisplayName("encounters, notes, admissions, discharge, transfers and isolation refuse the role")
    void encounterAndStaySurfaces() {
        assertOffChart(EncounterController.class);
        assertOffChart(EncounterTreatmentController.class);
        assertOffChart(NursingNoteController.class);
        // The order-set catalog is configuration; applying one to a live admission is not.
        assertOffChart(AdmissionController.class, "/order-sets");
        assertKept(AdmissionController.class, "POST /order-sets");
        assertRefuses(AdmissionOrderSetController.class, "POST /{orderSetId}/apply/{admissionId}");
        assertOffChart(DischargeSummaryController.class);
        assertOffChart(DischargeApprovalController.class);
        assertOffChart(TransferController.class);
        assertOffChart(IsolationController.class);
    }

    @Test
    @DisplayName("consultations, referrals, the in-basket and CDS acknowledgements refuse the role")
    void coordinationSurfaces() {
        assertOffChart(ConsultationController.class);
        // The overdue-expiry sweep is an ops action, not a read of anyone's chart.
        assertOffChart(GeneralReferralController.class, "/admin/expire-overdue");
        assertKept(GeneralReferralController.class, "POST /admin/expire-overdue");
        assertOffChart(ObgynReferralController.class, "/reports/summary");
        assertOffChart(InBasketController.class);
        assertOffChart(CdsAcknowledgementController.class);
    }
}
