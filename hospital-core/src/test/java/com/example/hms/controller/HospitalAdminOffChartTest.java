package com.example.hms.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
 * method security. The matcher layer in {@code SecurityConfig} is covered by
 * {@code SecurityConfigChartMatcherTest}.
 */
@DisplayName("HOSPITAL_ADMIN off the clinical chart (E9 #67 b1)")
class HospitalAdminOffChartTest {

    private static final String ROLE = "HOSPITAL_ADMIN";

    /** Every guard on the class keyed "VERB path"; a class-level guard is keyed "CLASS". */
    private static Map<String, String> guardsOf(Class<?> controller) {
        Map<String, String> guards = new LinkedHashMap<>();
        PreAuthorize onClass = controller.getAnnotation(PreAuthorize.class);
        if (onClass != null) {
            guards.put("CLASS", onClass.value());
        }
        for (Method method : controller.getDeclaredMethods()) {
            PreAuthorize pre = method.getAnnotation(PreAuthorize.class);
            if (pre == null) {
                continue;
            }
            guards.put(verbAndPath(method), pre.value());
        }
        return guards;
    }

    private static String verbAndPath(Method method) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null) {
            return "? " + method.getName();
        }
        String verb = mapping.method().length > 0 ? mapping.method()[0].name() : "?";
        String path = mapping.path().length > 0 ? mapping.path()[0] : "";
        return verb + " " + path;
    }

    private static String pathOf(String where) {
        int space = where.indexOf(' ');
        return space < 0 ? "" : where.substring(space + 1);
    }

    /** Every guard on the controller refuses the role, except those whose path starts with a kept prefix. */
    private static void assertOffChart(Class<?> controller, String... keptPathPrefixes) {
        Map<String, String> guards = guardsOf(controller);
        assertThat(guards).as("%s has guards", controller.getSimpleName()).isNotEmpty();
        guards.forEach((where, guard) -> {
            String path = pathOf(where);
            boolean kept = Arrays.stream(keptPathPrefixes).anyMatch(path::startsWith);
            if (!kept) {
                assertThat(guard).as("%s %s", controller.getSimpleName(), where).doesNotContain(ROLE);
            }
        });
    }

    private static void assertKept(Class<?> controller, String where) {
        assertThat(guardsOf(controller)).as("%s declares %s", controller.getSimpleName(), where).containsKey(where);
        assertThat(guardsOf(controller).get(where)).as("%s %s keeps HOSPITAL_ADMIN", controller.getSimpleName(), where)
            .contains(ROLE);
    }

    @Test
    @DisplayName("the chart sub-resources of /patients refuse the role; demographics keep it")
    void patientChartSubResources() {
        Map<String, String> guards = guardsOf(PatientController.class);
        for (String where : new String[] {
            "GET /{id}/allergies", "GET /{id}/diagnoses", "GET /{id}/chart-updates",
            "GET /{patientId}/chart-updates/{updateId}"}) {
            assertThat(guards).as("PatientController declares %s", where).containsKey(where);
            assertThat(guards.get(where)).as("PatientController %s", where).doesNotContain(ROLE);
        }
        // What a hospital admin is for: the demographic record and its registration.
        assertKept(PatientController.class, "GET /{id}");
        assertKept(PatientController.class, "PUT /{id}");
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
        assertThat(guardsOf(AdmissionOrderSetController.class).get("POST /{orderSetId}/apply/{admissionId}"))
            .as("applying an order set to an admission").doesNotContain(ROLE);
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
