package com.example.hms.controller;

import com.example.hms.config.PreAuthorizeMatcherPairingTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard drift a hand-audit kept re-finding, as a ratchet (tasklist
 * "Standing platform debt": the guard drift this keeps re-finding needs a
 * ratchet, not another hand-audit): roughly 280 guards list DOCTOR and NURSE
 * together and a minority list DOCTOR without NURSE, with controllers carrying
 * both shapes. {@code NurseReadAccessTest} pins four endpoints by hand and
 * cannot see the rest.
 *
 * <p>Every handler whose effective {@code @PreAuthorize} (method, else class)
 * admits DOCTOR but not NURSE is listed below with its controller's reason.
 * A new such handler fails this test until it is listed — the decision "a
 * nurse may not" is made on purpose, not by copying a neighbour's guard — and
 * a listed handler that no longer has the shape must be removed.
 */
class GuardDriftRatchetTest {

    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String ROLE_NURSE = "ROLE_NURSE";

    /** "Controller.method" → why DOCTOR without NURSE. */
    private static final Map<String, String> DOCTOR_WITHOUT_NURSE = doctorWithoutNurse();

    @Test
    @DisplayName("a guard admitting DOCTOR but not NURSE is a listed decision, never a copied neighbour")
    void doctorWithoutNurseIsADecision() {
        Set<String> found = new TreeSet<>();
        for (Class<?> controller : controllers()) {
            PreAuthorize classGuard = AnnotatedElementUtils.findMergedAnnotation(controller, PreAuthorize.class);
            for (Method method : controller.getDeclaredMethods()) {
                if (AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null) {
                    continue;
                }
                PreAuthorize guard = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
                if (guard == null) {
                    guard = classGuard;
                }
                if (guard == null) {
                    continue;
                }
                Set<String> roles = PreAuthorizeMatcherPairingTest.rolesAdmittedBy(guard.value());
                if (roles.contains(ROLE_DOCTOR) && !roles.contains(ROLE_NURSE)) {
                    found.add(controller.getSimpleName() + "." + method.getName());
                }
            }
        }
        assertThat(found.size()).as("the scan reached the guards").isPositive();

        Set<String> unlisted = new TreeSet<>(found);
        unlisted.removeAll(DOCTOR_WITHOUT_NURSE.keySet());
        Set<String> stale = new TreeSet<>(DOCTOR_WITHOUT_NURSE.keySet());
        stale.removeAll(found);
        assertThat(unlisted)
            .as("guards admitting DOCTOR but not NURSE, not listed: add NURSE, or list the handler with its reason")
            .isEmpty();
        assertThat(stale).as("listed handlers that no longer have the shape: remove them").isEmpty();
        DOCTOR_WITHOUT_NURSE.forEach((handler, reason) -> assertThat(reason).as(handler).isNotBlank());
    }

    private static final String ADMISSIONCONTROLLER_REASON =
        "Admission, discharge and order-set orders are an ordering physician act";
    private static final String ADMISSIONORDERSETCONTROLLER_REASON =
        "Applying an admission order set is an ordering physician act";
    private static final String APPOINTMENTCONTROLLER_REASON =
        "The doctor's own schedule (the path is the doctor role's)";
    private static final String BILLINGINVOICECONTROLLER_REASON =
        "Billing read, not a nursing surface; as found 2026-09-26, pending the clinical decision in tasklist \"Nurse/midwife read drift\"";
    private static final String BIRTHPLANCONTROLLER_REASON =
        "Provider review and deletion of a birth plan; maternity cluster (c) as found 2026-09-26, pending the clinical decision in tasklist \"Nurse/midwife read drift\"";
    private static final String CONSULTATIONCONTROLLER_REASON =
        "Consultations are addressed to and worked by the consulting physician; (b) as found 2026-09-26, pending the clinical decision in tasklist \"Nurse/midwife read drift\"";
    private static final String DIGITALSIGNATURECONTROLLER_REASON =
        "Revoking a signature and its audit trail belong to the signer";
    private static final String DISCHARGEAPPROVALCONTROLLER_REASON =
        "Discharge approval is a physician act";
    private static final String DISCHARGESUMMARYCONTROLLER_REASON =
        "Authoring and finalising the discharge summary is a physician act";
    private static final String DISPENSECONTROLLER_REASON =
        "Prescriber-facing pharmacy read; the pharmacy files are owned by another PR of this batch";
    private static final String ENCOUNTERCONTROLLER_REASON =
        "Starting an encounter as the attending and co-signing a note are physician acts";
    private static final String GENERALREFERRALCONTROLLER_REASON =
        "Referrals are addressed to and worked by the receiving provider";
    private static final String IMAGINGORDERCONTROLLER_REASON =
        "The ordering provider's signature and status transitions";
    private static final String IMAGINGRESULTCONTROLLER_REASON =
        "Report authoring, signing and critical-result acknowledgement by the reading or ordering physician";
    private static final String LABRESULTCONTROLLER_REASON =
        "Result sign-off by the ordering physician (#724)";
    private static final String MATERNALHISTORYCONTROLLER_REASON =
        "Risk scoring and review sign-off; maternity cluster (c) as found 2026-09-26, pending the clinical decision in tasklist \"Nurse/midwife read drift\"";
    private static final String MECONTROLLER_REASON =
        "The physician's own dashboard panels; /critical-alerts is question (a) as found 2026-09-26, pending the clinical decision in tasklist \"Nurse/midwife read drift\"";
    private static final String MEDICATIONHISTORYCONTROLLER_REASON =
        "Pharmacy fills; the pharmacy files are owned by another PR of this batch";
    private static final String MORTALITYCONTROLLER_REASON =
        "Certifying and amending a death record is a physician act";
    private static final String OBGYNREFERRALCONTROLLER_REASON =
        "The OB/GYN referral workflow of the referring and receiving physicians; maternity cluster (c) as found 2026-09-26, pending the clinical decision in tasklist \"Nurse/midwife read drift\"";
    private static final String PATIENTCONTROLLER_REASON =
        "Diagnoses are made by physicians; the doctor record and timeline are the physician's own views";
    private static final String PRESCRIPTIONCONTROLLER_REASON =
        "Signing, co-signing and resolving a clarification are prescribing acts";
    private static final String PROCEDUREORDERCONTROLLER_REASON =
        "The ordering provider cancels the order";
    private static final String ROIWORKLISTCONTROLLER_REASON =
        "Release-of-information decisions; as found 2026-09-26, pending the clinical decision in tasklist \"Nurse/midwife read drift\"";
    private static final String STOCKOUTROUTINGCONTROLLER_REASON =
        "Prescriber-facing pharmacy read; the pharmacy files are owned by another PR of this batch";
    private static final String TRANSFUSIONCONTROLLER_REASON =
        "Ordering blood is a prescriber act (TransfusionController.PRESCRIBER)";
    private static final String TREATMENTCONTROLLER_REASON =
        "Treatment authoring is a physician act";
    private static final String TREATMENTPLANCONTROLLER_REASON =
        "Physician review of a treatment plan";
    private static final String ULTRASOUNDCONTROLLER_REASON =
        "Ultrasound ordering and reporting; maternity cluster (c) as found 2026-09-26, pending the clinical decision in tasklist \"Nurse/midwife read drift\"";

    private static Map<String, String> doctorWithoutNurse() {
        Map<String, String> listed = new TreeMap<>();
        listed.put("AdmissionController.admitPatient", ADMISSIONCONTROLLER_REASON);
        listed.put("AdmissionController.cancelAdmission", ADMISSIONCONTROLLER_REASON);
        listed.put("AdmissionController.createOrderSet", ADMISSIONCONTROLLER_REASON);
        listed.put("AdmissionController.deactivateOrderSet", ADMISSIONCONTROLLER_REASON);
        listed.put("AdmissionController.dischargePatient", ADMISSIONCONTROLLER_REASON);
        listed.put("AdmissionOrderSetController.apply", ADMISSIONORDERSETCONTROLLER_REASON);
        listed.put("AppointmentController.getAppointmentsByDoctorIdForDoctorRole", APPOINTMENTCONTROLLER_REASON);
        listed.put("BillingInvoiceController.getInvoicesByPatientId", BILLINGINVOICECONTROLLER_REASON);
        listed.put("BirthPlanController.deleteBirthPlan", BIRTHPLANCONTROLLER_REASON);
        listed.put("BirthPlanController.getPendingReviews", BIRTHPLANCONTROLLER_REASON);
        listed.put("BirthPlanController.providerReview", BIRTHPLANCONTROLLER_REASON);
        listed.put("ConsultationController.acknowledgeConsultation", CONSULTATIONCONTROLLER_REASON);
        listed.put("ConsultationController.assignConsultation", CONSULTATIONCONTROLLER_REASON);
        listed.put("ConsultationController.completeConsultation", CONSULTATIONCONTROLLER_REASON);
        listed.put("ConsultationController.declineConsultation", CONSULTATIONCONTROLLER_REASON);
        listed.put("ConsultationController.getConsultationsAssignedTo", CONSULTATIONCONTROLLER_REASON);
        listed.put("ConsultationController.getConsultationsForHospital", CONSULTATIONCONTROLLER_REASON);
        listed.put("ConsultationController.getMyConsultations", CONSULTATIONCONTROLLER_REASON);
        listed.put("ConsultationController.getPendingConsultations", CONSULTATIONCONTROLLER_REASON);
        listed.put("ConsultationController.reassignConsultation", CONSULTATIONCONTROLLER_REASON);
        listed.put("ConsultationController.scheduleConsultation", CONSULTATIONCONTROLLER_REASON);
        listed.put("ConsultationController.startConsultation", CONSULTATIONCONTROLLER_REASON);
        listed.put("ConsultationController.updateConsultation", CONSULTATIONCONTROLLER_REASON);
        listed.put("DigitalSignatureController.getSignatureAuditTrail", DIGITALSIGNATURECONTROLLER_REASON);
        listed.put("DigitalSignatureController.revokeSignature", DIGITALSIGNATURECONTROLLER_REASON);
        listed.put("DischargeApprovalController.approve", DISCHARGEAPPROVALCONTROLLER_REASON);
        listed.put("DischargeApprovalController.getPendingForHospital", DISCHARGEAPPROVALCONTROLLER_REASON);
        listed.put("DischargeApprovalController.reject", DISCHARGEAPPROVALCONTROLLER_REASON);
        listed.put("DischargeSummaryController.createDischargeSummary", DISCHARGESUMMARYCONTROLLER_REASON);
        listed.put("DischargeSummaryController.deleteDischargeSummary", DISCHARGESUMMARYCONTROLLER_REASON);
        listed.put("DischargeSummaryController.finalizeDischargeSummary", DISCHARGESUMMARYCONTROLLER_REASON);
        listed.put("DischargeSummaryController.getDischargeSummariesByProvider", DISCHARGESUMMARYCONTROLLER_REASON);
        listed.put("DischargeSummaryController.getUnfinalizedDischargeSummaries", DISCHARGESUMMARYCONTROLLER_REASON);
        listed.put("DischargeSummaryController.updateDischargeSummary", DISCHARGESUMMARYCONTROLLER_REASON);
        listed.put("DispenseController.listByPrescription", DISPENSECONTROLLER_REASON);
        listed.put("EncounterController.cosignEncounterNote", ENCOUNTERCONTROLLER_REASON);
        listed.put("EncounterController.startEncounter", ENCOUNTERCONTROLLER_REASON);
        listed.put("GeneralReferralController.acknowledgeReferral", GENERALREFERRALCONTROLLER_REASON);
        listed.put("GeneralReferralController.cancelReferral", GENERALREFERRALCONTROLLER_REASON);
        listed.put("GeneralReferralController.completeReferral", GENERALREFERRALCONTROLLER_REASON);
        listed.put("GeneralReferralController.getOverdueReferrals", GENERALREFERRALCONTROLLER_REASON);
        listed.put("GeneralReferralController.getReferralsByHospital", GENERALREFERRALCONTROLLER_REASON);
        listed.put("GeneralReferralController.getReferralsByReceivingProvider", GENERALREFERRALCONTROLLER_REASON);
        listed.put("GeneralReferralController.rejectReferral", GENERALREFERRALCONTROLLER_REASON);
        listed.put("GeneralReferralController.scheduleReferral", GENERALREFERRALCONTROLLER_REASON);
        listed.put("GeneralReferralController.startReferral", GENERALREFERRALCONTROLLER_REASON);
        listed.put("ImagingOrderController.captureSignature", IMAGINGORDERCONTROLLER_REASON);
        listed.put("ImagingOrderController.updateStatus", IMAGINGORDERCONTROLLER_REASON);
        listed.put("ImagingResultController.acknowledgeCriticalResult", IMAGINGRESULTCONTROLLER_REASON);
        listed.put("ImagingResultController.createReport", IMAGINGRESULTCONTROLLER_REASON);
        listed.put("ImagingResultController.getReportsByHospital", IMAGINGRESULTCONTROLLER_REASON);
        listed.put("ImagingResultController.signReport", IMAGINGRESULTCONTROLLER_REASON);
        listed.put("ImagingResultController.updateReport", IMAGINGRESULTCONTROLLER_REASON);
        listed.put("ImagingResultController.updateReportStatus", IMAGINGRESULTCONTROLLER_REASON);
        listed.put("LabResultController.signLabResult", LABRESULTCONTROLLER_REASON);
        listed.put("MaternalHistoryController.calculateRiskScore", MATERNALHISTORYCONTROLLER_REASON);
        listed.put("MaternalHistoryController.getPendingReview", MATERNALHISTORYCONTROLLER_REASON);
        listed.put("MaternalHistoryController.getRequiringSpecialistReferral", MATERNALHISTORYCONTROLLER_REASON);
        listed.put("MaternalHistoryController.markAsReviewed", MATERNALHISTORYCONTROLLER_REASON);
        listed.put("MeController.getCriticalAlerts", MECONTROLLER_REASON);
        listed.put("MeController.getCriticalStrip", MECONTROLLER_REASON);
        listed.put("MeController.getInbox", MECONTROLLER_REASON);
        listed.put("MeController.getInboxCounts", MECONTROLLER_REASON);
        listed.put("MeController.getOnCallStatus", MECONTROLLER_REASON);
        listed.put("MeController.getPatientFlow", MECONTROLLER_REASON);
        listed.put("MeController.getRecentPatients", MECONTROLLER_REASON);
        listed.put("MeController.getResultReviewQueue", MECONTROLLER_REASON);
        listed.put("MeController.getRoomedPatients", MECONTROLLER_REASON);
        listed.put("MeController.getWorklist", MECONTROLLER_REASON);
        listed.put("MedicationHistoryController.createPharmacyFill", MEDICATIONHISTORYCONTROLLER_REASON);
        listed.put("MedicationHistoryController.updatePharmacyFill", MEDICATIONHISTORYCONTROLLER_REASON);
        listed.put("MortalityController.amendDeathRecord", MORTALITYCONTROLLER_REASON);
        listed.put("MortalityController.recordDeath", MORTALITYCONTROLLER_REASON);
        listed.put("ObgynReferralController.acknowledgeReferral", OBGYNREFERRALCONTROLLER_REASON);
        listed.put("ObgynReferralController.addMessage", OBGYNREFERRALCONTROLLER_REASON);
        listed.put("ObgynReferralController.completeReferral", OBGYNREFERRALCONTROLLER_REASON);
        listed.put("ObgynReferralController.createReferral", OBGYNREFERRALCONTROLLER_REASON);
        listed.put("ObgynReferralController.getReferralsForObgyn", OBGYNREFERRALCONTROLLER_REASON);
        listed.put("ObgynReferralController.startReferral", OBGYNREFERRALCONTROLLER_REASON);
        listed.put("PatientController.createPatientDiagnosis", PATIENTCONTROLLER_REASON);
        listed.put("PatientController.deletePatientDiagnosis", PATIENTCONTROLLER_REASON);
        listed.put("PatientController.getDoctorRecord", PATIENTCONTROLLER_REASON);
        listed.put("PatientController.getDoctorTimeline", PATIENTCONTROLLER_REASON);
        listed.put("PatientController.updatePatientDiagnosis", PATIENTCONTROLLER_REASON);
        listed.put("PrescriptionController.cosign", PRESCRIPTIONCONTROLLER_REASON);
        listed.put("PrescriptionController.resolveClarification", PRESCRIPTIONCONTROLLER_REASON);
        listed.put("PrescriptionController.sign", PRESCRIPTIONCONTROLLER_REASON);
        listed.put("ProcedureOrderController.cancelProcedureOrder", PROCEDUREORDERCONTROLLER_REASON);
        listed.put("RoiWorklistController.deny", ROIWORKLISTCONTROLLER_REASON);
        listed.put("RoiWorklistController.fulfil", ROIWORKLISTCONTROLLER_REASON);
        listed.put("StockOutRoutingController.listByPrescription", STOCKOUTROUTINGCONTROLLER_REASON);
        listed.put("TransfusionController.cancelRequest", TRANSFUSIONCONTROLLER_REASON);
        listed.put("TransfusionController.createRequest", TRANSFUSIONCONTROLLER_REASON);
        listed.put("TreatmentController.createTreatment", TREATMENTCONTROLLER_REASON);
        listed.put("TreatmentController.updateTreatment", TREATMENTCONTROLLER_REASON);
        listed.put("TreatmentPlanController.addReview", TREATMENTPLANCONTROLLER_REASON);
        listed.put("UltrasoundController.cancelOrder", ULTRASOUNDCONTROLLER_REASON);
        listed.put("UltrasoundController.createOrUpdateReport", ULTRASOUNDCONTROLLER_REASON);
        listed.put("UltrasoundController.createOrder", ULTRASOUNDCONTROLLER_REASON);
        listed.put("UltrasoundController.getAnatomyScanTemplate", ULTRASOUNDCONTROLLER_REASON);
        listed.put("UltrasoundController.getHighRiskOrders", ULTRASOUNDCONTROLLER_REASON);
        listed.put("UltrasoundController.getNuchalTranslucencyTemplate", ULTRASOUNDCONTROLLER_REASON);
        listed.put("UltrasoundController.getOrdersByHospitalId", ULTRASOUNDCONTROLLER_REASON);
        listed.put("UltrasoundController.getPendingOrders", ULTRASOUNDCONTROLLER_REASON);
        listed.put("UltrasoundController.getReportsRequiringFollowUp", ULTRASOUNDCONTROLLER_REASON);
        listed.put("UltrasoundController.getReportsWithAnomalies", ULTRASOUNDCONTROLLER_REASON);
        listed.put("UltrasoundController.markPatientNotified", ULTRASOUNDCONTROLLER_REASON);
        listed.put("UltrasoundController.markReportReviewed", ULTRASOUNDCONTROLLER_REASON);
        listed.put("UltrasoundController.updateOrder", ULTRASOUNDCONTROLLER_REASON);
        return listed;
    }

    private static List<Class<?>> controllers() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        CachingMetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> found = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources("classpath*:com/example/hms/**/*.class")) {
                MetadataReader reader = factory.getMetadataReader(resource);
                String className = reader.getClassMetadata().getClassName();
                if (className.endsWith("Test") || className.endsWith("IT") || className.contains("$")) {
                    continue;
                }
                if (reader.getAnnotationMetadata().hasAnnotation(RestController.class.getName())) {
                    found.add(Class.forName(className));
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        return found;
    }
}
