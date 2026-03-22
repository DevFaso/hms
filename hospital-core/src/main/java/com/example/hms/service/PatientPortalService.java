package com.example.hms.service;

import com.example.hms.payload.dto.portal.HealthSummaryDTO;
import com.example.hms.payload.dto.portal.PatientProfileDTO;
import com.example.hms.payload.dto.portal.PatientProfileUpdateDTO;
import com.example.hms.payload.dto.portal.CancelAppointmentRequestDTO;
import com.example.hms.payload.dto.portal.RescheduleAppointmentRequestDTO;
import com.example.hms.payload.dto.portal.PortalConsentRequestDTO;
import com.example.hms.payload.dto.portal.HomeVitalReadingDTO;
import com.example.hms.payload.dto.portal.MedicationRefillRequestDTO;
import com.example.hms.payload.dto.portal.MedicationRefillResponseDTO;
import com.example.hms.payload.dto.portal.CareTeamDTO;
import com.example.hms.payload.dto.portal.CareTeamContactDTO;
import com.example.hms.payload.dto.portal.AccessLogEntryDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceUpdateDTO;
import com.example.hms.payload.dto.portal.ProxyGrantRequestDTO;
import com.example.hms.payload.dto.portal.ProxyResponseDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import com.example.hms.payload.dto.medication.PatientMedicationResponseDTO;
import com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.BillingInvoiceResponseDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.discharge.DischargeSummaryResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Patient-portal ("MyChart") service — resolves patient identity from
 * authentication and delegates to existing clinical services.
 */
public interface PatientPortalService {

    /** Resolve the Patient UUID linked to the authenticated user. */
    UUID resolvePatientId(Authentication auth);

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 1 — Read-only endpoints
    // ══════════════════════════════════════════════════════════════════════

    // ── Profile ──────────────────────────────────────────────────────────
    PatientProfileDTO getMyProfile(Authentication auth);
    PatientProfileDTO updateMyProfile(Authentication auth, PatientProfileUpdateDTO dto);

    // ── Health summary (aggregated dashboard) ────────────────────────────
    HealthSummaryDTO getHealthSummary(Authentication auth, Locale locale);

    // ── Lab results ──────────────────────────────────────────────────────
    List<PatientLabResultResponseDTO> getMyLabResults(Authentication auth, int limit);

    // ── Medications ──────────────────────────────────────────────────────
    List<PatientMedicationResponseDTO> getMyMedications(Authentication auth, int limit);

    // ── Prescriptions ────────────────────────────────────────────────────
    List<PrescriptionResponseDTO> getMyPrescriptions(Authentication auth, Locale locale);

    // ── Vital signs ──────────────────────────────────────────────────────
    List<PatientVitalSignResponseDTO> getMyVitals(Authentication auth, int limit);

    // ── Encounters / visit history ───────────────────────────────────────
    List<EncounterResponseDTO> getMyEncounters(Authentication auth, Locale locale);

    // ── Appointments ─────────────────────────────────────────────────────
    List<AppointmentResponseDTO> getMyAppointments(Authentication auth, Locale locale);

    // ── Billing / invoices ───────────────────────────────────────────────
    Page<BillingInvoiceResponseDTO> getMyInvoices(Authentication auth, Pageable pageable, Locale locale);

    // ── Pay an invoice ───────────────────────────────────────────────────
    BillingInvoiceResponseDTO recordMyPayment(Authentication auth, UUID invoiceId, java.math.BigDecimal amount, Locale locale);

    // ── Consents ─────────────────────────────────────────────────────────
    Page<PatientConsentResponseDTO> getMyConsents(Authentication auth, Pageable pageable);

    // ── Immunizations ────────────────────────────────────────────────────
    List<ImmunizationResponseDTO> getMyImmunizations(Authentication auth);

    // ── Consultations ────────────────────────────────────────────────────
    List<ConsultationResponseDTO> getMyConsultations(Authentication auth);

    // ── Treatment plans ──────────────────────────────────────────────────
    Page<TreatmentPlanResponseDTO> getMyTreatmentPlans(Authentication auth, Pageable pageable);

    // ── Referrals ────────────────────────────────────────────────────────
    List<GeneralReferralResponseDTO> getMyReferrals(Authentication auth);

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 2 — Write / action endpoints ("Close the Functional Gaps")
    // ══════════════════════════════════════════════════════════════════════

    // ── Cancel own appointment ───────────────────────────────────────────
    AppointmentResponseDTO cancelMyAppointment(Authentication auth, CancelAppointmentRequestDTO dto, Locale locale);

    // ── Reschedule own appointment ───────────────────────────────────────
    AppointmentResponseDTO rescheduleMyAppointment(Authentication auth, RescheduleAppointmentRequestDTO dto, Locale locale);

    // ── Grant data-sharing consent ───────────────────────────────────────
    PatientConsentResponseDTO grantMyConsent(Authentication auth, PortalConsentRequestDTO dto);

    // ── Revoke data-sharing consent ──────────────────────────────────────
    void revokeMyConsent(Authentication auth, UUID fromHospitalId, UUID toHospitalId);

    // ── Record home vital sign ───────────────────────────────────────────
    PatientVitalSignResponseDTO recordHomeVital(Authentication auth, HomeVitalReadingDTO dto);

    // ── Request medication refill ────────────────────────────────────────
    MedicationRefillResponseDTO requestMedicationRefill(Authentication auth, MedicationRefillRequestDTO dto);

    // ── View my refill requests ──────────────────────────────────────────
    Page<MedicationRefillResponseDTO> getMyRefills(Authentication auth, Pageable pageable);

    // ── Cancel a pending refill request ──────────────────────────────────
    MedicationRefillResponseDTO cancelMyRefill(Authentication auth, UUID refillId);
    // ── Notification preferences ──────────────────────────────────────────
    List<NotificationPreferenceDTO> getMyNotificationPreferences(Authentication auth);
    NotificationPreferenceDTO setMyNotificationPreference(Authentication auth, NotificationPreferenceUpdateDTO dto);
    void resetMyNotificationPreferences(Authentication auth);

    // ── Vital sign trends ────────────────────────────────────────────────
    List<PatientVitalSignResponseDTO> getMyVitalTrends(Authentication auth, int months);

    // ── Upcoming vaccinations ────────────────────────────────────────────
    List<com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO> getMyUpcomingVaccinations(Authentication auth, int months);
    // ── After-visit summaries (discharge summaries) ──────────────────────
    List<DischargeSummaryResponseDTO> getMyAfterVisitSummaries(Authentication auth, Locale locale);

    // ── Care team ────────────────────────────────────────────────────────
    CareTeamDTO getMyCareTeam(Authentication auth);

    /** Messageable care team contacts (current + recent PCPs) for routing a chat. */
    List<CareTeamContactDTO> getMessageableCareTeam(Authentication auth);

    // ── Access log (who viewed my records) ───────────────────────────────
    Page<AccessLogEntryDTO> getMyAccessLog(Authentication auth, Pageable pageable);

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 3 — Proxy / Family Access
    // ══════════════════════════════════════════════════════════════════════

    /** List proxies I have granted (people who can view my data). */
    List<ProxyResponseDTO> getMyProxies(Authentication auth);

    /** Grant proxy access to another user. */
    ProxyResponseDTO grantProxy(Authentication auth, ProxyGrantRequestDTO dto);

    /** Revoke a previously granted proxy. */
    void revokeProxy(Authentication auth, UUID proxyId);

    /** List patients whose data I can view as proxy. */
    List<ProxyResponseDTO> getMyProxyAccess(Authentication auth);

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 4 — Lab Orders (status tracking)
    // ══════════════════════════════════════════════════════════════════════

    List<com.example.hms.payload.dto.LabOrderResponseDTO> getMyLabOrders(Authentication auth, Locale locale);

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 5 — Imaging Orders + Results
    // ══════════════════════════════════════════════════════════════════════

    List<com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO> getMyImagingOrders(Authentication auth);

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 6 — Pharmacy Fill History
    // ══════════════════════════════════════════════════════════════════════

    List<com.example.hms.payload.dto.medication.PharmacyFillResponseDTO> getMyPharmacyFills(Authentication auth, Locale locale);

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 7 — Procedure Orders
    // ══════════════════════════════════════════════════════════════════════

    List<com.example.hms.payload.dto.procedure.ProcedureOrderResponseDTO> getMyProcedureOrders(Authentication auth);

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 8 — Admission / Hospitalization History
    // ══════════════════════════════════════════════════════════════════════

    List<com.example.hms.payload.dto.AdmissionResponseDTO> getMyAdmissions(Authentication auth);

    com.example.hms.payload.dto.AdmissionResponseDTO getMyCurrentAdmission(Authentication auth);

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 9 — Patient Education Progress
    // ══════════════════════════════════════════════════════════════════════

    List<com.example.hms.payload.dto.education.PatientEducationProgressResponseDTO> getMyEducationProgress(Authentication auth);

    List<com.example.hms.payload.dto.education.PatientEducationProgressResponseDTO> getMyInProgressEducation(Authentication auth);

    List<com.example.hms.payload.dto.education.PatientEducationProgressResponseDTO> getMyCompletedEducation(Authentication auth);

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 10 — Browse Education Resources
    // ══════════════════════════════════════════════════════════════════════

    List<com.example.hms.payload.dto.education.EducationResourceResponseDTO> getMyEducationResources(Authentication auth);

    List<com.example.hms.payload.dto.education.EducationResourceResponseDTO> searchMyEducationResources(Authentication auth, String query);

    List<com.example.hms.payload.dto.education.EducationResourceResponseDTO> getMyEducationResourcesByCategory(
            Authentication auth, com.example.hms.enums.EducationCategory category);

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 11 — Medical Records Self-Download
    // ══════════════════════════════════════════════════════════════════════

    /** Returns raw PDF or CSV bytes for the patient's own record (no bilateral consent required). */
    byte[] downloadMyRecord(Authentication auth, String format);

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 12 — Lab Result Trends
    // ══════════════════════════════════════════════════════════════════════

    List<com.example.hms.payload.dto.lab.LabResultTrendDTO> getMyLabResultTrends(Authentication auth);

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 13 — Online Check-In
    // ══════════════════════════════════════════════════════════════════════

    com.example.hms.payload.dto.AppointmentResponseDTO checkInMyAppointment(
            Authentication auth, java.util.UUID appointmentId, java.util.Locale locale);

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 14 — Appointment Booking
    // ══════════════════════════════════════════════════════════════════════

    /** List active departments at the patient's hospital (for booking form). */
    List<com.example.hms.payload.dto.DepartmentMinimalDTO> getMyDepartments(Authentication auth, Locale locale);

    /** List providers/staff in a department (for booking form). */
    List<com.example.hms.payload.dto.StaffMinimalDTO> getDepartmentProviders(Authentication auth, UUID departmentId, Locale locale);

    /** Request a new appointment (status = PENDING). */
    com.example.hms.payload.dto.AppointmentSummaryDTO bookMyAppointment(
            Authentication auth, com.example.hms.payload.dto.portal.PortalAppointmentRequestDTO dto, Locale locale);

    // ════════════════════════════════════════════════════════════════════
    // FEATURE 15 — Pre-Visit Questionnaires
    // ════════════════════════════════════════════════════════════════════

    /** Returns active questionnaires for the patient's hospital that have not yet been answered. */
    List<com.example.hms.payload.dto.questionnaire.PreVisitQuestionnaireDTO> getMyPendingQuestionnaires(
            Authentication auth);

    /** Returns all questionnaire responses previously submitted by the patient. */
    List<com.example.hms.payload.dto.questionnaire.QuestionnaireResponseDTO> getMySubmittedQuestionnaires(
            Authentication auth);

    /** Submits the patient's answers for a pre-visit questionnaire. Each questionnaire can only be submitted once. */
    com.example.hms.payload.dto.questionnaire.QuestionnaireResponseDTO submitMyQuestionnaire(
            Authentication auth, com.example.hms.payload.dto.questionnaire.QuestionnaireResponseSubmitDTO dto);

    // ════════════════════════════════════════════════════════════════════
    // FEATURE 16 — OpenNotes (Visit Notes)
    // ════════════════════════════════════════════════════════════════════

    /** Returns the clinical note for a specific encounter that belongs to the patient. */
    com.example.hms.payload.dto.EncounterNoteResponseDTO getMyEncounterNote(
            Authentication auth, UUID encounterId);

    // ════════════════════════════════════════════════════════════════════
    // FEATURE 17 — Post-Visit Instructions
    // ════════════════════════════════════════════════════════════════════

    /** Returns discharge / post-visit instructions for a specific encounter. */
    com.example.hms.payload.dto.portal.PortalDischargeInstructionsDTO getMyPostVisitInstructions(
            Authentication auth, UUID encounterId, Locale locale);

    // ════════════════════════════════════════════════════════════════════
    // FEATURE 18 — Immunization Certificate
    // ════════════════════════════════════════════════════════════════════

    /** Generates and returns a PDF immunization certificate for the patient. */
    byte[] generateMyImmunizationCertificate(Authentication auth);

    // ════════════════════════════════════════════════════════════════════
    // FEATURE 19 — Health Maintenance Reminders
    // ════════════════════════════════════════════════════════════════════

    /** Returns all active health maintenance reminders for the patient. */
    List<com.example.hms.payload.dto.portal.PortalHealthReminderDTO> getMyHealthReminders(Authentication auth);

    /** Marks a health maintenance reminder as completed by the patient. */
    com.example.hms.payload.dto.portal.PortalHealthReminderDTO completeMyHealthReminder(
            Authentication auth, UUID reminderId);

    // ════════════════════════════════════════════════════════════════════
    // FEATURE 20 — Treatment Progress Tracker
    // ════════════════════════════════════════════════════════════════════

    /** Returns all progress entries logged by the patient for a treatment plan. */
    List<com.example.hms.payload.dto.portal.PortalProgressEntryDTO> getMyTreatmentPlanProgress(
            Authentication auth, UUID planId);

    /** Logs a new patient-reported progress entry against a treatment plan. */
    com.example.hms.payload.dto.portal.PortalProgressEntryDTO logMyTreatmentProgress(
            Authentication auth, UUID planId,
            com.example.hms.payload.dto.portal.PortalProgressEntryRequestDTO request);

    // ════════════════════════════════════════════════════════════════════
    // FEATURE 21 — Patient-Reported Outcomes (PROs)
    // ════════════════════════════════════════════════════════════════════

    /** Returns all patient-reported outcome entries for the authenticated patient. */
    List<com.example.hms.payload.dto.portal.PortalOutcomeDTO> getMyOutcomes(Authentication auth);

    /** Records a new patient-reported outcome entry. */
    com.example.hms.payload.dto.portal.PortalOutcomeDTO reportMyOutcome(
            Authentication auth,
            com.example.hms.payload.dto.portal.PortalOutcomeRequestDTO request);
}
