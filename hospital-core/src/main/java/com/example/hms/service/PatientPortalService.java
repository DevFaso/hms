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
}
