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
import com.example.hms.payload.dto.portal.PortalBookAppointmentRequestDTO;
import com.example.hms.payload.dto.portal.PortalConsentRequestDTO;
import com.example.hms.payload.dto.portal.RescheduleAppointmentRequestDTO;
import com.example.hms.payload.dto.portal.PatientPaymentRequestDTO;
import com.example.hms.enums.PatientDocumentType;
import com.example.hms.model.Notification;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceUpdateDTO;
import com.example.hms.payload.dto.portal.PatientDocumentRequestDTO;
import com.example.hms.payload.dto.portal.PatientDocumentResponseDTO;
import com.example.hms.payload.dto.portal.PreCheckInRequestDTO;
import com.example.hms.payload.dto.portal.PreCheckInResponseDTO;
import com.example.hms.payload.dto.portal.QuestionnaireDTO;
import com.example.hms.payload.dto.portal.ProxyGrantRequestDTO;
import com.example.hms.payload.dto.portal.ProxyResponseDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyClaimResponseDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyPaymentResponseDTO;
import com.example.hms.service.NotificationService;
import com.example.hms.service.PatientDocumentService;
import com.example.hms.service.PatientPortalService;
import com.example.hms.service.pharmacy.PharmacyClaimService;
import com.example.hms.service.pharmacy.PharmacyPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final PatientDocumentService documentService;
    private final NotificationService notificationService;
    private final PharmacyPaymentService pharmacyPaymentService;
    private final PharmacyClaimService pharmacyClaimService;

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

    @Operation(summary = "Pay an invoice",
            description = "Make a payment towards an outstanding invoice. The invoice must be in SENT or PARTIALLY_PAID status.")
    @PostMapping("/billing/invoices/{invoiceId}/pay")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<BillingInvoiceResponseDTO>> payMyInvoice(
            Authentication auth,
            @PathVariable UUID invoiceId,
            @Valid @RequestBody PatientPaymentRequestDTO dto) {
        Locale locale = LocaleContextHolder.getLocale();
        BillingInvoiceResponseDTO result = portalService.recordMyPayment(auth, invoiceId, dto.getAmount(), locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(result));
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

    // ── Schedule own appointment ─────────────────────────────────────────

    @Operation(summary = "Schedule a new appointment",
            description = "Patients can self-schedule an appointment at a hospital they are registered with")
    @PostMapping("/appointments")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<AppointmentResponseDTO>> scheduleMyAppointment(
            Authentication auth, @Valid @RequestBody PortalBookAppointmentRequestDTO dto) {
        Locale locale = LocaleContextHolder.getLocale();
        AppointmentResponseDTO result = portalService.scheduleMyAppointment(auth, dto, locale);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.success(result));
    }

    // ── Booking-form lookups ─────────────────────────────────────────────

    @Operation(summary = "List my hospitals",
            description = "Returns hospitals where the patient has an active registration (for booking form)")
    @GetMapping("/booking/hospitals")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<Map<String, Object>>>> getMyHospitals(
            Authentication auth) {
        return ResponseEntity.ok(ApiResponseWrapper.success(portalService.getMyHospitals(auth)));
    }

    @Operation(summary = "List departments for a hospital",
            description = "Returns departments at the specified hospital (for booking form)")
    @GetMapping("/booking/hospitals/{hospitalId}/departments")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<Map<String, Object>>>> getDepartments(
            @PathVariable UUID hospitalId) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                portalService.getDepartmentsForHospital(hospitalId)));
    }

    @Operation(summary = "List providers for a department",
            description = "Returns active providers (doctors/nurses) in a specific hospital department (for booking form)")
    @GetMapping("/booking/hospitals/{hospitalId}/departments/{departmentId}/providers")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<Map<String, Object>>>> getProviders(
            @PathVariable UUID hospitalId, @PathVariable UUID departmentId) {
        return ResponseEntity.ok(ApiResponseWrapper.success(
                portalService.getProvidersForDepartment(hospitalId, departmentId)));
    }

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

    // ── Proxy / Family Access ─────────────────────────────────────────────

    @Operation(summary = "List my proxy grants",
            description = "People I have granted access to view my health data")
    @GetMapping("/proxies")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<ProxyResponseDTO>>> getMyProxies(Authentication auth) {
        List<ProxyResponseDTO> proxies = portalService.getMyProxies(auth);
        return ResponseEntity.ok(ApiResponseWrapper.success(proxies));
    }

    @Operation(summary = "Grant proxy access",
            description = "Allow another user (family member/caregiver) to view your portal data")
    @PostMapping("/proxies")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<ProxyResponseDTO>> grantProxy(
            Authentication auth, @Valid @RequestBody ProxyGrantRequestDTO dto) {
        ProxyResponseDTO result = portalService.grantProxy(auth, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.success(result));
    }

    @Operation(summary = "Revoke proxy access",
            description = "Remove a previously granted proxy/family access")
    @DeleteMapping("/proxies/{proxyId}")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<Void>> revokeProxy(
            Authentication auth, @PathVariable UUID proxyId) {
        portalService.revokeProxy(auth, proxyId);
        return ResponseEntity.ok(ApiResponseWrapper.success(null));
    }

    @Operation(summary = "List patients I can access as proxy",
            description = "Patients who have granted me proxy/family access to their data")
    @GetMapping("/proxy-access")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseWrapper<List<ProxyResponseDTO>>> getMyProxyAccess(Authentication auth) {
        List<ProxyResponseDTO> access = portalService.getMyProxyAccess(auth);
        return ResponseEntity.ok(ApiResponseWrapper.success(access));
    }

    // ── Proxy data-viewing endpoints ──────────────────────────────────────

    @Operation(summary = "View grantor's appointments as proxy")
    @GetMapping("/proxy-access/{patientId}/appointments")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseWrapper<List<AppointmentResponseDTO>>> getProxyAppointments(
            Authentication auth, @PathVariable UUID patientId) {
        Locale locale = LocaleContextHolder.getLocale();
        List<AppointmentResponseDTO> data = portalService.getProxyAppointments(auth, patientId, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(data));
    }

    @Operation(summary = "View grantor's medications as proxy")
    @GetMapping("/proxy-access/{patientId}/medications")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseWrapper<List<PatientMedicationResponseDTO>>> getProxyMedications(
            Authentication auth, @PathVariable UUID patientId,
            @RequestParam(defaultValue = "50") int limit) {
        List<PatientMedicationResponseDTO> data = portalService.getProxyMedications(auth, patientId, limit);
        return ResponseEntity.ok(ApiResponseWrapper.success(data));
    }

    @Operation(summary = "View grantor's lab results as proxy")
    @GetMapping("/proxy-access/{patientId}/lab-results")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseWrapper<List<PatientLabResultResponseDTO>>> getProxyLabResults(
            Authentication auth, @PathVariable UUID patientId,
            @RequestParam(defaultValue = "50") int limit) {
        List<PatientLabResultResponseDTO> data = portalService.getProxyLabResults(auth, patientId, limit);
        return ResponseEntity.ok(ApiResponseWrapper.success(data));
    }

    @Operation(summary = "View grantor's billing invoices as proxy")
    @GetMapping("/proxy-access/{patientId}/billing")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseWrapper<Page<BillingInvoiceResponseDTO>>> getProxyBilling(
            Authentication auth, @PathVariable UUID patientId,
            @PageableDefault(size = 20) Pageable pageable) {
        Locale locale = LocaleContextHolder.getLocale();
        Page<BillingInvoiceResponseDTO> data = portalService.getProxyBilling(auth, patientId, pageable, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(data));
    }

    @Operation(summary = "View grantor's health records as proxy")
    @GetMapping("/proxy-access/{patientId}/records")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponseWrapper<HealthSummaryDTO>> getProxyRecords(
            Authentication auth, @PathVariable UUID patientId) {
        Locale locale = LocaleContextHolder.getLocale();
        HealthSummaryDTO data = portalService.getProxyRecords(auth, patientId, locale);
        return ResponseEntity.ok(ApiResponseWrapper.success(data));
    }

    // ── Documents (Phase 3) ───────────────────────────────────────────────

    @Operation(summary = "Upload a personal document",
            description = "Accepts PDF, JPG, PNG, TIFF, DOC, DOCX — max 20 MB")
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<PatientDocumentResponseDTO>> uploadDocument(
            Authentication auth,
            @RequestPart("file") MultipartFile file,
            @RequestPart("documentType") String documentTypeStr,
            @RequestParam(required = false) String collectionDate,
            @RequestParam(required = false) String notes) throws IOException {
        PatientDocumentType documentType = PatientDocumentType.valueOf(documentTypeStr.toUpperCase());
        PatientDocumentRequestDTO request = PatientDocumentRequestDTO.builder()
                .documentType(documentType)
                .collectionDate(collectionDate != null ? java.time.LocalDate.parse(collectionDate) : null)
                .notes(notes)
                .build();
        PatientDocumentResponseDTO result = documentService.uploadDocument(auth, file, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.success(result));
    }

    @Operation(summary = "List my uploaded documents")
    @GetMapping("/documents")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<Page<PatientDocumentResponseDTO>>> listDocuments(
            Authentication auth,
            @RequestParam(required = false) PatientDocumentType documentType,
            @PageableDefault(size = 20, sort = "createdAt", direction = org.springframework.data.domain.Sort.Direction.DESC) Pageable pageable) {
        Page<PatientDocumentResponseDTO> page = documentService.listDocuments(auth, documentType, pageable);
        return ResponseEntity.ok(ApiResponseWrapper.success(page));
    }

    @Operation(summary = "Get a single uploaded document")
    @GetMapping("/documents/{documentId}")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<PatientDocumentResponseDTO>> getDocument(
            Authentication auth, @PathVariable UUID documentId) {
        PatientDocumentResponseDTO doc = documentService.getDocument(auth, documentId);
        return ResponseEntity.ok(ApiResponseWrapper.success(doc));
    }

    @Operation(summary = "Delete an uploaded document")
    @DeleteMapping("/documents/{documentId}")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<Void>> deleteDocument(
            Authentication auth, @PathVariable UUID documentId) {
        documentService.deleteDocument(auth, documentId);
        return ResponseEntity.ok(ApiResponseWrapper.success(null));
    }

    // ── Notifications (Phase 3) ───────────────────────────────────────────

    @Operation(summary = "List my notifications",
            description = "Returns paginated notifications for the authenticated patient. Filter by read status.")
    @GetMapping("/notifications")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<Page<Notification>>> getMyNotifications(
            Authentication auth,
            @RequestParam(required = false) Boolean read,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        String username = auth.getName();
        Page<Notification> page = notificationService.getNotificationsForUser(username, read, search, pageable);
        return ResponseEntity.ok(ApiResponseWrapper.success(page));
    }

    @Operation(summary = "Get unread notification count")
    @GetMapping("/notifications/unread-count")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<Map<String, Long>>> getUnreadCount(Authentication auth) {
        String username = auth.getName();
        long count = notificationService.countUnreadForUser(username);
        return ResponseEntity.ok(ApiResponseWrapper.success(Map.of("unreadCount", count)));
    }

    @Operation(summary = "Mark a notification as read")
    @PutMapping("/notifications/{notificationId}/read")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<Void>> markNotificationRead(
            Authentication auth, @PathVariable UUID notificationId) {
        notificationService.markAsRead(notificationId, auth.getName());
        return ResponseEntity.ok(ApiResponseWrapper.success(null));
    }

    @Operation(summary = "Mark all notifications as read")
    @PutMapping("/notifications/read-all")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<Map<String, Integer>>> markAllNotificationsRead(Authentication auth) {
        int updated = notificationService.markAllReadForUser(auth.getName());
        return ResponseEntity.ok(ApiResponseWrapper.success(Map.of("updated", updated)));
    }

    @Operation(summary = "Get my notification preferences")
    @GetMapping("/notification-preferences")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<NotificationPreferenceDTO>>> getNotificationPreferences(
            Authentication auth) {
        // Resolve userId to fetch preferences
        List<NotificationPreferenceDTO> prefs = portalService.getMyNotificationPreferences(auth);
        return ResponseEntity.ok(ApiResponseWrapper.success(prefs));
    }

    @Operation(summary = "Update my notification preferences")
    @PutMapping("/notification-preferences")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<NotificationPreferenceDTO>>> updateNotificationPreferences(
            Authentication auth,
            @Valid @RequestBody List<NotificationPreferenceUpdateDTO> updates) {
        List<NotificationPreferenceDTO> prefs = portalService.updateMyNotificationPreferences(auth, updates);
        return ResponseEntity.ok(ApiResponseWrapper.success(prefs));
    }

    // ══════════════════════════════════════════════════════════════════════
    // MVP 4 — Pre-Visit Questionnaires & Pre-Check-In
    // ══════════════════════════════════════════════════════════════════════

    @Operation(summary = "Get questionnaires for an upcoming appointment",
            description = "Returns active questionnaires assigned to the appointment's hospital and department")
    @GetMapping("/appointments/{appointmentId}/questionnaires")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<QuestionnaireDTO>>> getQuestionnaires(
            Authentication auth, @PathVariable UUID appointmentId) {
        List<QuestionnaireDTO> questionnaires = portalService.getQuestionnairesForAppointment(auth, appointmentId);
        return ResponseEntity.ok(ApiResponseWrapper.success(questionnaires));
    }

    @Operation(summary = "Submit pre-check-in",
            description = "Patient submits demographics updates, questionnaire responses, and consent before their visit")
    @PostMapping("/appointments/{appointmentId}/pre-checkin")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<PreCheckInResponseDTO>> submitPreCheckIn(
            Authentication auth,
            @PathVariable UUID appointmentId,
            @Valid @RequestBody PreCheckInRequestDTO dto) {
        // Ensure the path variable and body match
        dto.setAppointmentId(appointmentId);
        PreCheckInResponseDTO result = portalService.submitPreCheckIn(auth, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseWrapper.success(result));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Medical & Family History
    // ══════════════════════════════════════════════════════════════════════

    @Operation(summary = "Get my medical history (diagnoses)")
    @GetMapping("/medical-history")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<com.example.hms.payload.dto.portal.PatientDiagnosisSummaryDTO>>> getMyMedicalHistory(
            Authentication auth) {
        return ResponseEntity.ok(ApiResponseWrapper.success(portalService.getMyMedicalHistory(auth)));
    }

    @Operation(summary = "Get my surgical history")
    @GetMapping("/surgical-history")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<com.example.hms.payload.dto.PatientSurgicalHistoryResponseDTO>>> getMySurgicalHistory(
            Authentication auth) {
        return ResponseEntity.ok(ApiResponseWrapper.success(portalService.getMySurgicalHistory(auth)));
    }

    @Operation(summary = "Get my family history")
    @GetMapping("/family-history")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<List<com.example.hms.payload.dto.medicalhistory.FamilyHistoryResponseDTO>>> getMyFamilyHistory(
            Authentication auth) {
        return ResponseEntity.ok(ApiResponseWrapper.success(portalService.getMyFamilyHistory(auth)));
    }

    @Operation(summary = "Get my social history")
    @GetMapping("/social-history")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<com.example.hms.payload.dto.medicalhistory.SocialHistoryResponseDTO>> getMySocialHistory(
            Authentication auth) {
        return ResponseEntity.ok(ApiResponseWrapper.success(portalService.getMySocialHistory(auth)));
    }

    // ── Pharmacy payments / claims (patient self-service) ────────────────
    // IDOR-safe: patient ID is resolved from the JWT via portalService.resolvePatientId(auth).
    // The staff-facing endpoints in PharmacyPaymentController / PharmacyClaimController
    // explicitly exclude ROLE_PATIENT; patients must use these /me/patient routes instead.

    @Operation(summary = "List my pharmacy payments (self-service)")
    @GetMapping("/pharmacy/payments")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<Page<PharmacyPaymentResponseDTO>>> getMyPharmacyPayments(
            Authentication auth,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID patientId = portalService.resolvePatientId(auth);
        return ResponseEntity.ok(ApiResponseWrapper.success(
                pharmacyPaymentService.listByPatientForSelf(patientId, pageable)));
    }

    @Operation(summary = "List my pharmacy insurance claims (self-service)")
    @GetMapping("/pharmacy/claims")
    @PreAuthorize("hasAuthority('ROLE_PATIENT')")
    public ResponseEntity<ApiResponseWrapper<Page<PharmacyClaimResponseDTO>>> getMyPharmacyClaims(
            Authentication auth,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID patientId = portalService.resolvePatientId(auth);
        return ResponseEntity.ok(ApiResponseWrapper.success(
                pharmacyClaimService.listByPatientForSelf(patientId, pageable)));
    }
}
