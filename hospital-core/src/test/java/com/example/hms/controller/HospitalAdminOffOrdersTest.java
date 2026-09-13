package com.example.hms.controller;

import com.example.hms.controller.pharmacy.MtmReviewController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.example.hms.controller.GuardIndex.assertKept;
import static com.example.hms.controller.GuardIndex.assertOffChart;
import static com.example.hms.controller.GuardIndex.assertRefuses;

/**
 * E9 #67, decision D5, slice (b2) — orders, results, imaging, medications,
 * maternity, procedures, transfusion, signatures, programmes and PRO.
 *
 * <p>The keeps are asserted too, so the cut cannot over-reach without the
 * build saying so: the two critical-escalation sweeps, the delete-only
 * corrections (lab result, treatment, history rows), the mortality register,
 * signature governance, the admin panel views, prenatal scheduling, recall
 * scheduling, the PRO instrument catalog, sensitivity tagging, labels and
 * front-desk lookups. See {@link HospitalAdminOffChartTest} for slice (b1).
 */
@DisplayName("HOSPITAL_ADMIN off orders, results and care plans (E9 #67 b2)")
class HospitalAdminOffOrdersTest {

    @Test
    @DisplayName("prescriptions, medication therapy reviews and the pharmacy directory refuse the role")
    void medications() {
        assertOffChart(PrescriptionController.class);
        assertOffChart(MtmReviewController.class);
        assertOffChart(PharmacyDirectoryController.class);
    }

    @Test
    @DisplayName("lab orders, results and specimens refuse the role; the sweep and the delete keep it")
    void laboratory() {
        assertOffChart(LabOrderController.class);
        assertOffChart(LabSpecimenController.class);
        assertOffChart(LabResultController.class, "/critical-escalation/run", "/{id}");
        assertRefuses(LabResultController.class, "POST /{id}/acknowledge");
        assertRefuses(LabResultController.class, "POST /{id}/critical-read-back");
        assertKept(LabResultController.class, "POST /critical-escalation/run");
        assertKept(LabResultController.class, "DELETE /{id}");
        assertOffChart(MicroCultureController.class);
    }

    @Test
    @DisplayName("imaging, DICOM, ultrasound and transfusion refuse the role; the imaging sweep keeps it")
    void imagingAndTransfusion() {
        assertOffChart(ImagingOrderController.class);
        assertOffChart(ImagingResultController.class, "/critical-escalation/run");
        assertKept(ImagingResultController.class, "POST /critical-escalation/run");
        assertOffChart(DicomProxyController.class);
        assertOffChart(UltrasoundController.class);
        assertOffChart(TransfusionController.class);
    }

    @Test
    @DisplayName("maternity care refuses the role; prenatal scheduling and the mortality register keep it")
    void maternity() {
        assertOffChart(BirthPlanController.class);
        assertOffChart(HighRiskPregnancyCarePlanController.class);
        assertOffChart(LaborController.class);
        assertOffChart(PostpartumCareController.class);
        assertOffChart(NewbornAssessmentController.class);
        // Booking a prenatal visit is front-desk work: RECEPTIONIST leads that list.
        assertKept(PrenatalSchedulingController.class, "POST /schedule");
        // Certifying or amending a death is a clinician act; the statutory register is not.
        assertRefuses(MortalityController.class, "POST /deaths");
        assertRefuses(MortalityController.class, "POST /deaths/{recordId}/amend");
        assertKept(MortalityController.class, "GET /register");
    }

    @Test
    @DisplayName("procedures, directives, treatments and plans refuse the role; the delete-only corrections keep it")
    void proceduresAndPlans() {
        assertOffChart(ProcedureOrderController.class);
        assertOffChart(AdvanceDirectiveController.class);
        assertOffChart(TreatmentPlanController.class);
        assertOffChart(TreatmentController.class, "/{id}");
        assertRefuses(TreatmentController.class, "GET /{id}");
        assertRefuses(TreatmentController.class, "PUT /{id}");
        assertKept(TreatmentController.class, "DELETE /{id}");
        assertKept(MedicalHistoryController.class, "DELETE /immunizations/{id}");
        assertKept(MedicationHistoryController.class, "DELETE /pharmacy-fills/{fillId}");
    }

    @Test
    @DisplayName("signatures on reports refuse the role; signature governance keeps it")
    void signatures() {
        assertRefuses(DigitalSignatureController.class, "POST /verify");
        assertRefuses(DigitalSignatureController.class, "GET /report/{reportType}/{reportId}");
        assertRefuses(DigitalSignatureController.class, "GET /{signatureId}");
        assertRefuses(DigitalSignatureController.class, "GET /report/{reportType}/{reportId}/is-signed");
        assertKept(DigitalSignatureController.class, "POST /{signatureId}/revoke");
        assertKept(DigitalSignatureController.class, "GET /all");
        assertKept(DigitalSignatureController.class, "GET /{signatureId}/audit-trail");
    }

    @Test
    @DisplayName("per-patient panels, programmes and PRO responses refuse the role; the admin views and catalogs keep it")
    void panelsProgrammesAndPro() {
        assertOffChart(PatientPanelController.class);
        assertOffChart(ProgramEnrollmentController.class);
        assertOffChart(ProgramRegistryController.class);
        assertOffChart(ProResponseController.class);
        assertRefuses(FileUploadController.class, "POST /chart-attachments");
        assertRefuses(FileUploadController.class, "POST /referral-attachments");
        assertKept(PanelWorklistController.class, "GET /overview");
        assertKept(ProInstrumentController.class, "GET ");
        assertKept(PatientRecallController.class, "CLASS");
        assertKept(SensitivityTaggingController.class, "GET /encounters/{encounterId}/sensitivity");
        assertKept(PrintLabelController.class, "GET /patients/{patientId}/wristband.pdf");
        assertKept(LookupController.class, "CLASS");
    }
}
