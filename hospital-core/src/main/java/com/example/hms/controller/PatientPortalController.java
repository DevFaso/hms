package com.example.hms.controller;

import com.example.hms.payload.dto.ApiResponseWrapper;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.BillingInvoiceResponseDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.payload.dto.discharge.DischargeSummaryResponseDTO;
import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import com.example.hms.payload.dto.medication.PatientMedicationResponseDTO;
import com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO;
import com.example.hms.payload.dto.portal.AccessLogEntryDTO;
import com.example.hms.payload.dto.portal.CancelAppointmentRequestDTO;
import com.example.hms.payload.dto.portal.CareTeamDTO;
import com.example.hms.payload.dto.portal.HealthSummaryDTO;
import com.example.hms.payload.dto.portal.HomeVitalReadingDTO;
import com.example.hms.payload.dto.portal.MedicationRefillRequestDTO;
import com.example.hms.payload.dto.portal.MedicationRefillResponseDTO;
import com.example.hms.payload.dto.portal.PatientProfileDTO;
import com.example.hms.payload.dto.portal.PatientProfileUpdateDTO;
import com.example.hms.payload.dto.portal.PortalConsentRequestDTO;
import com.example.hms.payload.dto.portal.RescheduleAppointmentRequestDTO;
import com.example.hms.service.PatientPortalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Patient portal ("MyChart") controller — all endpoints resolve the patient
 * from the JWT, so no patient ID appears in URLs (prevents IDOR attacks).
 * <p>
 * Every endpoint requires {@code ROLE_PATIENT} and delegates to
 * {@link PatientPortalService} which reuses existing clinical services.
 */
@RestController
@RequestMapping("/me/patient")
@RequiredArgsConstructor
@Tag(name = "Patient Portal", description = "Self-service endpoints for patients (MyChart equivalent)")
public class PatientPortalController {

    private final PatientPortalService portalService;

    // ── Profile ──────────────────────────────────────────────────────────

    @Operation(summary = "View my profile",
            description = "Returns the patient's demographics, contact info, and medical basics")
    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<PatientProfileDTO>> getMyProfile(Authentication auth) {
        PatientProfileDTO profile = portalService.getMyProfile(auth);
        return ResponseEntity.ok(ApiResponseWrapper.success(profile));
    }

    @Operation(summary = "Update my contact info",
            description = "Patients can update phone, email, address, emergency contacts, and preferred pharmacy")
    @PutMapping("/profile")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<PatientProfileDTO>> updateMyProfile(
            Authentication auth, @Valid @RequestBody PatientProfileUpdateDTO dto) {
        PatientProfileDTO updated = portalService.updateMyProfile(auth, dto);
        return ResponseEntity.ok(ApiResponseWrapper.success(updated));
    }

    // ── Health Summary ───────────────────────────────────────────────────

    @Operation(summary = "Get health summary dashboard",
            description = "Aggregated view: profile, recent labs, meds, vitals, immunizations, allergies, conditions")
    @GetMapping("/health-summary")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<HealthSummaryDTO>> getHealthSummary(Authentication auth) {
        Locale locale = LocaleContextHolder.getLocale();
        HealthSummaryDTO summary = portalService.getHealthSummary(auth, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(summary));
    }

    // ── Lab Results ──────────────────────────────────────────────────────

    @Operation(summary = "View my lab results")
    @GetMapping("/lab-results")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<PatientLabResultResponseDTO>>> getMyLabResults(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit) {
        List<PatientLabResultResponseDTO> results = portalService.getMyLabResults(auth, limit);
        return ResponseEntity.ok(ApiResponseWrapper.success(results));
    }

    // ── Medications ──────────────────────────────────────────────────────

    @Operation(summary = "View my current medications")
    @GetMapping("/medications")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<PatientMedicationResponseDTO>>> getMyMedications(
            Authentication auth,
            @RequestParam(defaultValue = "20") int limit) {
        List<PatientMedicationResponseDTO> meds = portalService.getMyMedications(auth, limit);
        return ResponseEntity.ok(ApiResponseWrapper.success(meds));
    }

    // ── Prescriptions ────────────────────────────────────────────────────

    @Operation(summary = "View my prescriptions")
    @GetMapping("/prescriptions")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<PrescriptionResponseDTO>>> getMyPrescriptions(
            Authentication auth) {
        Locale locale = LocaleContextHolder.getLocale();
        List<PrescriptionResponseDTO> prescriptions = portalService.getMyPrescriptions(auth, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(prescriptions));
    }

    // ── Vital Signs ──────────────────────────────────────────────────────

    @Operation(summary = "View my vital signs")
    @GetMapping("/vitals")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<PatientVitalSignResponseDTO>>> getMyVitals(
            Authentication auth,
            @RequestParam(defaultValue = "10") int limit) {
        List<PatientVitalSignResponseDTO> vitals = portalService.getMyVitals(auth, limit);
        return ResponseEntity.ok(ApiResponseWrapper.success(vitals));
    }

    // ── Encounters (visit history) ───────────────────────────────────────

    @Operation(summary = "View my visit history")
    @GetMapping("/encounters")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<EncounterResponseDTO>>> getMyEncounters(
            Authentication auth) {
        Locale locale = LocaleContextHolder.getLocale();
        List<EncounterResponseDTO> encounters = portalService.getMyEncounters(auth, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(encounters));
    }

    // ── Appointments ─────────────────────────────────────────────────────

    @Operation(summary = "View my appointments")
    @GetMapping("/appointments")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<AppointmentResponseDTO>>> getMyAppointments(
            Authentication auth) {
        Locale locale = LocaleContextHolder.getLocale();
        List<AppointmentResponseDTO> appointments = portalService.getMyAppointments(auth, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(appointments));
    }

    // ── Billing / Invoices ───────────────────────────────────────────────

    @Operation(summary = "View my billing statements")
    @GetMapping("/billing/invoices")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<Page<BillingInvoiceResponseDTO>>> getMyInvoices(
            Authentication auth,
            @PageableDefault(size = 10) Pageable pageable) {
        Locale locale = LocaleContextHolder.getLocale();
        Page<BillingInvoiceResponseDTO> invoices = portalService.getMyInvoices(auth, pageable, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(invoices));
    }

    // ── Consents ─────────────────────────────────────────────────────────

    @Operation(summary = "View my data-sharing consents")
    @GetMapping("/consents")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<Page<PatientConsentResponseDTO>>> getMyConsents(
            Authentication auth,
            @PageableDefault(size = 10) Pageable pageable) {
        Page<PatientConsentResponseDTO> consents = portalService.getMyConsents(auth, pageable);
        return ResponseEntity.ok(ApiResponseWrapper.success(consents));
    }

    // ── Immunizations ────────────────────────────────────────────────────

    @Operation(summary = "View my immunization records")
    @GetMapping("/immunizations")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<ImmunizationResponseDTO>>> getMyImmunizations(
            Authentication auth) {
        List<ImmunizationResponseDTO> immunizations = portalService.getMyImmunizations(auth);
        return ResponseEntity.ok(ApiResponseWrapper.success(immunizations));
    }

    // ── Consultations ────────────────────────────────────────────────────

    @Operation(summary = "View my consultations")
    @GetMapping("/consultations")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<ConsultationResponseDTO>>> getMyConsultations(
            Authentication auth) {
        List<ConsultationResponseDTO> consultations = portalService.getMyConsultations(auth);
        return ResponseEntity.ok(ApiResponseWrapper.success(consultations));
    }

    // ── Treatment Plans ──────────────────────────────────────────────────

    @Operation(summary = "View my treatment plans")
    @GetMapping("/treatment-plans")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<Page<TreatmentPlanResponseDTO>>> getMyTreatmentPlans(
            Authentication auth,
            @PageableDefault(size = 10) Pageable pageable) {
        Page<TreatmentPlanResponseDTO> plans = portalService.getMyTreatmentPlans(auth, pageable);
        return ResponseEntity.ok(ApiResponseWrapper.success(plans));
    }

    // ── Referrals ────────────────────────────────────────────────────────

    @Operation(summary = "View my referrals")
    @GetMapping("/referrals")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<GeneralReferralResponseDTO>>> getMyReferrals(
            Authentication auth) {
        List<GeneralReferralResponseDTO> referrals = portalService.getMyReferrals(auth);
        return ResponseEntity.ok(ApiResponseWrapper.success(referrals));
    }

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 2 — Write / action endpoints ("Close the Functional Gaps")
    // ══════════════════════════════════════════════════════════════════════

    // ── Cancel own appointment ───────────────────────────────────────────

    @Operation(summary = "Cancel my appointment",
            description = "Patients can cancel their own upcoming appointments")
    @PutMapping("/appointments/cancel")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<AppointmentResponseDTO>> cancelMyAppointment(
            Authentication auth, @Valid @RequestBody CancelAppointmentRequestDTO dto) {
        Locale locale = LocaleContextHolder.getLocale();
        AppointmentResponseDTO result = portalService.cancelMyAppointment(auth, dto, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(result));
    }

    // ── Reschedule own appointment ───────────────────────────────────────

    @Operation(summary = "Reschedule my appointment",
            description = "Patients can request rescheduling of their own appointments")
    @PutMapping("/appointments/reschedule")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<AppointmentResponseDTO>> rescheduleMyAppointment(
            Authentication auth, @Valid @RequestBody RescheduleAppointmentRequestDTO dto) {
        Locale locale = LocaleContextHolder.getLocale();
        AppointmentResponseDTO result = portalService.rescheduleMyAppointment(auth, dto, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(result));
    }

    // ── Grant data-sharing consent ───────────────────────────────────────

    @Operation(summary = "Grant data-sharing consent",
            description = "Allow one hospital to share your records with another hospital")
    @PostMapping("/consents")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<PatientConsentResponseDTO>> grantConsent(
            Authentication auth, @Valid @RequestBody PortalConsentRequestDTO dto) {
        PatientConsentResponseDTO result = portalService.grantMyConsent(auth, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.success(result));
    }

    // ── Revoke data-sharing consent ──────────────────────────────────────

    @Operation(summary = "Revoke data-sharing consent",
            description = "Revoke a previously granted consent between two hospitals")
    @DeleteMapping("/consents")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<Void>> revokeConsent(
            Authentication auth,
            @RequestParam UUID fromHospitalId,
            @RequestParam UUID toHospitalId) {
        portalService.revokeMyConsent(auth, fromHospitalId, toHospitalId);
        return ResponseEntity.ok(ApiResponseWrapper.success(null));
    }

    // ── Record home vital sign ───────────────────────────────────────────

    @Operation(summary = "Record a home vital reading",
            description = "Submit self-measured vitals (blood pressure, glucose, weight, etc.)")
    @PostMapping("/vitals")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<PatientVitalSignResponseDTO>> recordHomeVital(
            Authentication auth, @Valid @RequestBody HomeVitalReadingDTO dto) {
        PatientVitalSignResponseDTO result = portalService.recordHomeVital(auth, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.success(result));
    }

    // ── Request medication refill ────────────────────────────────────────

    @Operation(summary = "Request a medication refill",
            description = "Submit a refill request for an existing prescription")
    @PostMapping("/refills")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<MedicationRefillResponseDTO>> requestRefill(
            Authentication auth, @Valid @RequestBody MedicationRefillRequestDTO dto) {
        MedicationRefillResponseDTO result = portalService.requestMedicationRefill(auth, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.success(result));
    }

    // ── View my refill requests ──────────────────────────────────────────

    @Operation(summary = "View my refill requests",
            description = "List all medication refill requests and their statuses")
    @GetMapping("/refills")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<Page<MedicationRefillResponseDTO>>> getMyRefills(
            Authentication auth, @PageableDefault(size = 10) Pageable pageable) {
        Page<MedicationRefillResponseDTO> refills = portalService.getMyRefills(auth, pageable);
        return ResponseEntity.ok(ApiResponseWrapper.success(refills));
    }

    // ── Cancel a pending refill request ──────────────────────────────────

    @Operation(summary = "Cancel a pending refill request",
            description = "Cancel a refill request that is still in REQUESTED status")
    @PutMapping("/refills/{refillId}/cancel")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<MedicationRefillResponseDTO>> cancelMyRefill(
            Authentication auth, @PathVariable UUID refillId) {
        MedicationRefillResponseDTO result = portalService.cancelMyRefill(auth, refillId);
        return ResponseEntity.ok(ApiResponseWrapper.success(result));
    }

    // ── After-visit summaries ────────────────────────────────────────────

    @Operation(summary = "View my after-visit summaries",
            description = "Discharge summaries / after-visit instructions for completed encounters")
    @GetMapping("/after-visit-summaries")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<DischargeSummaryResponseDTO>>> getMyAfterVisitSummaries(
            Authentication auth) {
        Locale locale = LocaleContextHolder.getLocale();
        List<DischargeSummaryResponseDTO> summaries = portalService.getMyAfterVisitSummaries(auth, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(summaries));
    }

    // ── Care team ────────────────────────────────────────────────────────

    @Operation(summary = "View my care team",
            description = "Current and historical primary care providers")
    @GetMapping("/care-team")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<CareTeamDTO>> getMyCareTeam(Authentication auth) {
        CareTeamDTO careTeam = portalService.getMyCareTeam(auth);
        return ResponseEntity.ok(ApiResponseWrapper.success(careTeam));
    }

    // ── Access log ───────────────────────────────────────────────────────

    @Operation(summary = "View who accessed my records",
            description = "Audit log of access events on your patient data")
    @GetMapping("/access-log")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<Page<AccessLogEntryDTO>>> getMyAccessLog(
            Authentication auth, @PageableDefault(size = 20) Pageable pageable) {
        Page<AccessLogEntryDTO> logs = portalService.getMyAccessLog(auth, pageable);
        return ResponseEntity.ok(ApiResponseWrapper.success(logs));
    }
}
