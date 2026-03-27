package com.example.hms.service.impl;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.ProxyStatus;
import com.example.hms.enums.RefillStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProxy;
import com.example.hms.model.Prescription;
import com.example.hms.model.RefillRequest;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.BillingInvoiceResponseDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.PatientConsentRequestDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PatientPrimaryCareResponseDTO;
import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
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
import com.example.hms.payload.dto.portal.ProxyGrantRequestDTO;
import com.example.hms.payload.dto.portal.ProxyResponseDTO;
import com.example.hms.payload.dto.portal.RescheduleAppointmentRequestDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProxyRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.service.AppointmentService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.BillingInvoiceService;
import com.example.hms.service.ConsultationService;
import com.example.hms.service.DischargeSummaryService;
import com.example.hms.service.EncounterService;
import com.example.hms.service.GeneralReferralService;
import com.example.hms.service.ImmunizationService;
import com.example.hms.service.PatientConsentService;
import com.example.hms.service.PatientLabResultService;
import com.example.hms.service.PatientMedicationService;
import com.example.hms.service.PatientPortalService;
import com.example.hms.service.PatientPrimaryCareService;
import com.example.hms.service.PatientVitalSignService;
import com.example.hms.service.PrescriptionService;
import com.example.hms.service.TreatmentPlanService;
import com.example.hms.service.EmailService;
import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.mapper.AppointmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Patient-portal service — resolves the authenticated user's Patient record
 * and delegates to existing clinical services using patient-scoped queries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PatientPortalServiceImpl implements PatientPortalService {

    private static final String MSG_UNABLE_RESOLVE_USER = "Unable to resolve user from authentication";

    private final PatientRepository patientRepository;
    private final PatientProxyRepository patientProxyRepository;
    private final ControllerAuthUtils authUtils;

    // Existing clinical services — we delegate, not duplicate
    private final PatientLabResultService labResultService;
    private final PatientMedicationService medicationService;
    private final PatientVitalSignService vitalSignService;
    private final PatientConsentService consentService;
    private final ImmunizationService immunizationService;
    private final BillingInvoiceService billingInvoiceService;
    private final EncounterService encounterService;
    private final PrescriptionService prescriptionService;
    private final AppointmentService appointmentService;
    private final ConsultationService consultationService;
    private final TreatmentPlanService treatmentPlanService;
    private final GeneralReferralService referralService;

    // Phase 2 additions
    private final AppointmentRepository appointmentRepository;
    private final AppointmentMapper appointmentMapper;
    private final PrescriptionRepository prescriptionRepository;
    private final RefillRequestRepository refillRequestRepository;
    private final DischargeSummaryService dischargeSummaryService;
    private final PatientPrimaryCareService primaryCareService;
    private final AuditEventLogService auditEventLogService;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final com.example.hms.repository.UserRepository userRepository;
    private final EmailService emailService;

    @org.springframework.beans.factory.annotation.Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    private static final String RESCHEDULE_PATH = "/appointments/reschedule/";
    private static final String CANCEL_PATH = "/appointments/cancel/";

    // ── Identity resolution ──────────────────────────────────────────────

    @Override
    public UUID resolvePatientId(Authentication auth) {
        UUID userId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException(MSG_UNABLE_RESOLVE_USER));
        return patientRepository.findByUserId(userId)
                .map(Patient::getId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No patient record linked to your account. Contact your care team."));
    }

    // ── Profile ──────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PatientProfileDTO getMyProfile(Authentication auth) {
        Patient patient = findPatient(auth);
        return toProfileDTO(patient);
    }

    @Override
    @Transactional
    public PatientProfileDTO updateMyProfile(Authentication auth, PatientProfileUpdateDTO dto) {
        Patient patient = findPatient(auth);

        // Only update fields the patient is allowed to change
        if (dto.getPhoneNumberPrimary() != null)       patient.setPhoneNumberPrimary(dto.getPhoneNumberPrimary());
        if (dto.getPhoneNumberSecondary() != null)     patient.setPhoneNumberSecondary(dto.getPhoneNumberSecondary());
        if (dto.getEmail() != null)                    patient.setEmail(dto.getEmail());
        if (dto.getAddressLine1() != null)             patient.setAddressLine1(dto.getAddressLine1());
        if (dto.getAddressLine2() != null)             patient.setAddressLine2(dto.getAddressLine2());
        if (dto.getCity() != null)                     patient.setCity(dto.getCity());
        if (dto.getState() != null)                    patient.setState(dto.getState());
        if (dto.getZipCode() != null)                  patient.setZipCode(dto.getZipCode());
        if (dto.getCountry() != null)                  patient.setCountry(dto.getCountry());
        if (dto.getEmergencyContactName() != null)     patient.setEmergencyContactName(dto.getEmergencyContactName());
        if (dto.getEmergencyContactPhone() != null)    patient.setEmergencyContactPhone(dto.getEmergencyContactPhone());
        if (dto.getEmergencyContactRelationship() != null)
            patient.setEmergencyContactRelationship(dto.getEmergencyContactRelationship());
        if (dto.getPreferredPharmacy() != null)        patient.setPreferredPharmacy(dto.getPreferredPharmacy());

        patient = patientRepository.save(patient);
        log.info("Patient {} updated their profile", patient.getId());
        return toProfileDTO(patient);
    }

    // ── Health summary (aggregated) ──────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public HealthSummaryDTO getHealthSummary(Authentication auth, Locale locale) {
        Patient patient = findPatient(auth);
        UUID patientId = patient.getId();
        UUID hospitalId = resolvePatientHospitalId(patient);

        return HealthSummaryDTO.builder()
                .profile(toProfileDTO(patient))
                .recentLabResults(safeLabResults(patientId, hospitalId))
                .currentMedications(safeMedications(patientId, hospitalId))
                .recentVitals(safeVitals(patientId))
                .immunizations(safeImmunizations(patientId))
                .allergies(splitToList(patient.getAllergies()))
                .chronicConditions(splitToList(patient.getChronicConditions()))
                .build();
    }

    // ── Lab results ──────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<PatientLabResultResponseDTO> getMyLabResults(Authentication auth, int limit) {
        Patient patient = findPatient(auth);
        UUID hospitalId = resolvePatientHospitalId(patient);
        return labResultService.getLabResultsForPatient(patient.getId(), hospitalId, limit);
    }

    // ── Medications ──────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<PatientMedicationResponseDTO> getMyMedications(Authentication auth, int limit) {
        Patient patient = findPatient(auth);
        UUID hospitalId = resolvePatientHospitalId(patient);
        return medicationService.getMedicationsForPatient(patient.getId(), hospitalId, limit);
    }

    // ── Prescriptions ────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<PrescriptionResponseDTO> getMyPrescriptions(Authentication auth, Locale locale) {
        UUID patientId = resolvePatientId(auth);
        return prescriptionService.getPrescriptionsByPatientId(patientId, locale);
    }

    // ── Vital signs ──────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<PatientVitalSignResponseDTO> getMyVitals(Authentication auth, int limit) {
        UUID patientId = resolvePatientId(auth);
        return vitalSignService.getRecentVitals(patientId, null, limit);
    }

    // ── Encounters / visit history ───────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<EncounterResponseDTO> getMyEncounters(Authentication auth, Locale locale) {
        UUID patientId = resolvePatientId(auth);
        return encounterService.getEncountersByPatientId(patientId, locale);
    }

    // ── Appointments ─────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponseDTO> getMyAppointments(Authentication auth, Locale locale) {
        UUID patientId = resolvePatientId(auth);
        String username = auth.getName();
        return appointmentService.getAppointmentsByPatientId(patientId, locale, username);
    }

    // ── Billing / invoices ───────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<BillingInvoiceResponseDTO> getMyInvoices(Authentication auth, Pageable pageable, Locale locale) {
        UUID patientId = resolvePatientId(auth);
        return billingInvoiceService.getInvoicesByPatientId(patientId, pageable, locale);
    }

    // ── Pay an invoice ───────────────────────────────────────────────────

    @Override
    @Transactional
    public BillingInvoiceResponseDTO recordMyPayment(Authentication auth, UUID invoiceId, java.math.BigDecimal amount, Locale locale) {
        UUID patientId = resolvePatientId(auth);
        return billingInvoiceService.recordPayment(invoiceId, patientId, amount, locale);
    }

    // ── Consents ─────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<PatientConsentResponseDTO> getMyConsents(Authentication auth, Pageable pageable) {
        UUID patientId = resolvePatientId(auth);
        return consentService.getConsentsByPatient(patientId, pageable);
    }

    // ── Immunizations ────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<ImmunizationResponseDTO> getMyImmunizations(Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        return immunizationService.getImmunizationsByPatientId(patientId);
    }

    // ── Consultations ────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> getMyConsultations(Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        return consultationService.getConsultationsForPatient(patientId);
    }

    // ── Treatment plans ──────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<TreatmentPlanResponseDTO> getMyTreatmentPlans(Authentication auth, Pageable pageable) {
        UUID patientId = resolvePatientId(auth);
        return treatmentPlanService.listByPatient(patientId, pageable);
    }

    // ── Referrals ────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<GeneralReferralResponseDTO> getMyReferrals(Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        return referralService.getReferralsByPatient(patientId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 2 — Write / action endpoints ("Close the Functional Gaps")
    // ══════════════════════════════════════════════════════════════════════

    // ── Cancel own appointment ───────────────────────────────────────────

    @Override
    @Transactional
    public AppointmentResponseDTO cancelMyAppointment(Authentication auth,
                                                      CancelAppointmentRequestDTO dto,
                                                      Locale locale) {
        UUID patientId = resolvePatientId(auth);
        Appointment appointment = appointmentRepository.findById(dto.getAppointmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));

        requirePatientOwnership(appointment, patientId);

        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new BusinessException("Appointment is already cancelled");
        }
        if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
            throw new BusinessException("Cannot cancel a completed appointment");
        }

        appointment.setStatus(AppointmentStatus.CANCELLED);
        if (dto.getReason() != null && !dto.getReason().isBlank()) {
            String existingNotes = appointment.getNotes() != null ? appointment.getNotes() : "";
            appointment.setNotes(existingNotes + (existingNotes.isEmpty() ? "" : " | ")
                    + "Patient cancelled: " + dto.getReason());
        }
        appointmentRepository.save(appointment);
        log.info("Patient {} cancelled appointment {}", patientId, appointment.getId());

        // ── Send cancellation email to patient ──
        sendCancellationEmail(appointment);

        return appointmentMapper.toAppointmentResponseDTO(appointment);
    }

    // ── Reschedule own appointment ───────────────────────────────────────

    @Override
    @Transactional
    public AppointmentResponseDTO rescheduleMyAppointment(Authentication auth,
                                                          RescheduleAppointmentRequestDTO dto,
                                                          Locale locale) {
        UUID patientId = resolvePatientId(auth);
        Appointment appointment = appointmentRepository.findById(dto.getAppointmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));

        requirePatientOwnership(appointment, patientId);

        if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
            throw new BusinessException("Cannot reschedule a completed appointment");
        }
        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new BusinessException("Cannot reschedule a cancelled appointment");
        }

        appointment.setAppointmentDate(dto.getNewDate());
        appointment.setStartTime(dto.getNewStartTime());
        appointment.setEndTime(dto.getNewEndTime());
        appointment.setStatus(AppointmentStatus.RESCHEDULED);
        if (dto.getReason() != null && !dto.getReason().isBlank()) {
            String existingNotes = appointment.getNotes() != null ? appointment.getNotes() : "";
            appointment.setNotes(existingNotes + (existingNotes.isEmpty() ? "" : " | ")
                    + "Patient rescheduled: " + dto.getReason());
        }
        appointmentRepository.save(appointment);
        log.info("Patient {} rescheduled appointment {} to {}", patientId, appointment.getId(), dto.getNewDate());

        // ── Send reschedule confirmation email to patient ──
        sendRescheduleEmail(appointment);

        return appointmentMapper.toAppointmentResponseDTO(appointment);
    }

    // ── Grant data-sharing consent ───────────────────────────────────────

    @Override
    @Transactional
    public PatientConsentResponseDTO grantMyConsent(Authentication auth, PortalConsentRequestDTO dto) {
        UUID patientId = resolvePatientId(auth);
        requireHospitalRegistration(patientId, dto.getFromHospitalId());

        PatientConsentRequestDTO consentRequest = PatientConsentRequestDTO.builder()
                .patientId(patientId)
                .fromHospitalId(dto.getFromHospitalId())
                .toHospitalId(dto.getToHospitalId())
                .consentExpiration(dto.getConsentExpiration())
                .purpose(dto.getPurpose())
                .build();

        log.info("Patient {} granting consent from hospital {} to hospital {}",
                patientId, dto.getFromHospitalId(), dto.getToHospitalId());
        return consentService.grantConsent(consentRequest);
    }

    // ── Revoke data-sharing consent ──────────────────────────────────────

    @Override
    @Transactional
    public void revokeMyConsent(Authentication auth, UUID fromHospitalId, UUID toHospitalId) {
        UUID patientId = resolvePatientId(auth);
        requireHospitalRegistration(patientId, fromHospitalId);

        log.info("Patient {} revoking consent from hospital {} to hospital {}",
                patientId, fromHospitalId, toHospitalId);
        consentService.revokeConsent(patientId, fromHospitalId, toHospitalId);
    }

    // ── Record home vital sign ───────────────────────────────────────────

    @Override
    @Transactional
    public PatientVitalSignResponseDTO recordHomeVital(Authentication auth, HomeVitalReadingDTO dto) {
        Patient patient = findPatient(auth);
        UUID userId = patient.getUser().getId();

        PatientVitalSignRequestDTO vitalRequest = PatientVitalSignRequestDTO.builder()
                .source("PATIENT_REPORTED")
                .temperatureCelsius(dto.getTemperatureCelsius())
                .heartRateBpm(dto.getHeartRateBpm())
                .respiratoryRateBpm(dto.getRespiratoryRateBpm())
                .systolicBpMmHg(dto.getSystolicBpMmHg())
                .diastolicBpMmHg(dto.getDiastolicBpMmHg())
                .spo2Percent(dto.getSpo2Percent())
                .bloodGlucoseMgDl(dto.getBloodGlucoseMgDl())
                .weightKg(dto.getWeightKg())
                .bodyPosition(dto.getBodyPosition())
                .notes(dto.getNotes())
                .recordedAt(dto.getRecordedAt() != null ? dto.getRecordedAt() : java.time.LocalDateTime.now())
                .build();

        log.info("Patient {} recording home vital", patient.getId());
        return vitalSignService.recordVital(patient.getId(), vitalRequest, userId);
    }

    // ── Request medication refill ────────────────────────────────────────

    @Override
    @Transactional
    public MedicationRefillResponseDTO requestMedicationRefill(Authentication auth,
                                                               MedicationRefillRequestDTO dto) {
        Patient patient = findPatient(auth);

        Prescription prescription = prescriptionRepository.findById(dto.getPrescriptionId())
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found"));

        // Verify the prescription belongs to this patient
        if (!prescription.getPatient().getId().equals(patient.getId())) {
            throw new AccessDeniedException("You do not have access to this prescription");
        }

        RefillRequest refill = RefillRequest.builder()
                .patient(patient)
                .prescription(prescription)
                .status(RefillStatus.REQUESTED)
                .preferredPharmacy(dto.getPreferredPharmacy())
                .patientNotes(dto.getNotes())
                .build();

        refill = refillRequestRepository.save(refill);
        log.info("Patient {} requested refill {} for prescription {}",
                patient.getId(), refill.getId(), prescription.getId());
        return toRefillResponseDTO(refill);
    }

    // ── View my refill requests ──────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<MedicationRefillResponseDTO> getMyRefills(Authentication auth, Pageable pageable) {
        UUID patientId = resolvePatientId(auth);
        return refillRequestRepository.findByPatientId(patientId, pageable)
                .map(this::toRefillResponseDTO);
    }

    // ── Cancel a pending refill request ──────────────────────────────────

    @Override
    @Transactional
    public MedicationRefillResponseDTO cancelMyRefill(Authentication auth, UUID refillId) {
        UUID patientId = resolvePatientId(auth);

        RefillRequest refill = refillRequestRepository.findById(refillId)
                .orElseThrow(() -> new ResourceNotFoundException("Refill request not found"));

        if (!refill.getPatient().getId().equals(patientId)) {
            throw new AccessDeniedException("You do not have access to this refill request");
        }
        if (refill.getStatus() != RefillStatus.REQUESTED) {
            throw new BusinessException("Only pending refill requests can be cancelled. Current status: " + refill.getStatus());
        }

        refill.setStatus(RefillStatus.CANCELLED);
        refillRequestRepository.save(refill);
        log.info("Patient {} cancelled refill request {}", patientId, refillId);
        return toRefillResponseDTO(refill);
    }

    // ── After-visit summaries (discharge summaries) ──────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DischargeSummaryResponseDTO> getMyAfterVisitSummaries(Authentication auth, Locale locale) {
        UUID patientId = resolvePatientId(auth);
        return dischargeSummaryService.getDischargeSummariesByPatient(patientId, locale);
    }

    // ── Care team ────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public CareTeamDTO getMyCareTeam(Authentication auth) {
        UUID patientId = resolvePatientId(auth);

        CareTeamDTO.PrimaryCareEntry currentPcp = primaryCareService.getCurrentPrimaryCare(patientId)
                .map(this::toCareTeamEntry)
                .orElse(null);

        List<CareTeamDTO.PrimaryCareEntry> history = primaryCareService.getPrimaryCareHistory(patientId)
                .stream()
                .map(this::toCareTeamEntry)
                .toList();

        return CareTeamDTO.builder()
                .primaryCare(currentPcp)
                .primaryCareHistory(history)
                .build();
    }

    // ── Access log (who viewed my records) ───────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<AccessLogEntryDTO> getMyAccessLog(Authentication auth, Pageable pageable) {
        UUID patientId = resolvePatientId(auth);
        return auditEventLogService.getAuditLogsByTarget("PATIENT", patientId.toString(), pageable)
                .map(this::toAccessLogEntry);
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private Patient findPatient(Authentication auth) {
        UUID userId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException(MSG_UNABLE_RESOLVE_USER));
        return patientRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No patient record linked to your account. Contact your care team."));
    }

    /**
     * Resolve the patient's primary hospital ID.
     * Tries {@code patient.getHospitalId()} first, then falls back to the first
     * active hospital registration. Returns {@code null} if no hospital context is
     * available (sub-services must tolerate null in that case).
     */
    private UUID resolvePatientHospitalId(Patient patient) {
        if (patient.getHospitalId() != null) {
            return patient.getHospitalId();
        }
        return registrationRepository.findByPatientId(patient.getId()).stream()
                .filter(reg -> reg.isActive() && reg.getHospital() != null)
                .map(reg -> reg.getHospital().getId())
                .findFirst()
                .orElse(null);
    }

    private PatientProfileDTO toProfileDTO(Patient p) {
        return PatientProfileDTO.builder()
                .id(p.getId())
                .firstName(p.getFirstName())
                .lastName(p.getLastName())
                .middleName(p.getMiddleName())
                .dateOfBirth(p.getDateOfBirth())
                .gender(p.getGender())
                .phoneNumberPrimary(p.getPhoneNumberPrimary())
                .phoneNumberSecondary(p.getPhoneNumberSecondary())
                .email(p.getEmail())
                .addressLine1(p.getAddressLine1())
                .addressLine2(p.getAddressLine2())
                .city(p.getCity())
                .state(p.getState())
                .zipCode(p.getZipCode())
                .country(p.getCountry())
                .emergencyContactName(p.getEmergencyContactName())
                .emergencyContactPhone(p.getEmergencyContactPhone())
                .emergencyContactRelationship(p.getEmergencyContactRelationship())
                .bloodType(p.getBloodType())
                .allergies(p.getAllergies())
                .chronicConditions(p.getChronicConditions())
                .preferredPharmacy(p.getPreferredPharmacy())
                .username(p.getUser() != null ? p.getUser().getUsername() : null)
                .build();
    }

    /** Safe delegates — return empty list if service call fails (partial availability). */
    private List<PatientLabResultResponseDTO> safeLabResults(UUID patientId, UUID hospitalId) {
        try {
            return labResultService.getLabResultsForPatient(patientId, hospitalId, 5);
        } catch (Exception e) {
            log.warn("Failed to fetch lab results for health summary: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<PatientMedicationResponseDTO> safeMedications(UUID patientId, UUID hospitalId) {
        try {
            return medicationService.getMedicationsForPatient(patientId, hospitalId, 10);
        } catch (Exception e) {
            log.warn("Failed to fetch medications for health summary: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<PatientVitalSignResponseDTO> safeVitals(UUID patientId) {
        try {
            return vitalSignService.getRecentVitals(patientId, null, 5);
        } catch (Exception e) {
            log.warn("Failed to fetch vitals for health summary: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ImmunizationResponseDTO> safeImmunizations(UUID patientId) {
        try {
            return immunizationService.getImmunizationsByPatientId(patientId);
        } catch (Exception e) {
            log.warn("Failed to fetch immunizations for health summary: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<String> splitToList(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptyList();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Verify that the appointment belongs to the given patient. */
    private void requirePatientOwnership(Appointment appointment, UUID patientId) {
        if (!appointment.getPatient().getId().equals(patientId)) {
            throw new AccessDeniedException("This appointment does not belong to you");
        }
    }

    /**
     * Verify that the patient has an active registration at the given hospital.
     * Used by both grantMyConsent() and revokeMyConsent() to prevent a patient
     * from fabricating or revoking consent on behalf of a hospital they have
     * never attended (IDOR / privilege escalation).
     *
     * @throws BusinessException (HTTP 400) when no active registration is found.
     */
    private void requireHospitalRegistration(UUID patientId, UUID hospitalId) {
        boolean registered = registrationRepository
                .findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId)
                .isPresent();
        if (!registered) {
            throw new BusinessException(
                    "You are not registered at the specified source hospital and cannot manage consent on its behalf.");
        }
    }

    /** Map a RefillRequest entity to its response DTO. */
    private MedicationRefillResponseDTO toRefillResponseDTO(RefillRequest r) {
        String medName = null;
        if (r.getPrescription() != null && r.getPrescription().getMedicationName() != null) {
            medName = r.getPrescription().getMedicationName();
        }
        return MedicationRefillResponseDTO.builder()
                .id(r.getId())
                .prescriptionId(r.getPrescription().getId())
                .medicationName(medName)
                .patientId(r.getPatient().getId())
                .status(r.getStatus().name())
                .preferredPharmacy(r.getPreferredPharmacy())
                .notes(r.getPatientNotes())
                .providerNotes(r.getProviderNotes())
                .requestedAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }

    /** Map a PrimaryCareResponseDTO to a CareTeam entry. */
    private CareTeamDTO.PrimaryCareEntry toCareTeamEntry(PatientPrimaryCareResponseDTO pcp) {
        return CareTeamDTO.PrimaryCareEntry.builder()
                .id(pcp.getId())
                .hospitalId(pcp.getHospitalId())
                .doctorUserId(pcp.getDoctorUserId())
                .doctorDisplay(pcp.getDoctorDisplay())
                .startDate(pcp.getStartDate())
                .endDate(pcp.getEndDate())
                .current(pcp.isCurrent())
                .build();
    }

    /** Map an AuditEventLogResponseDTO to an AccessLogEntry for the patient portal. */
    private AccessLogEntryDTO toAccessLogEntry(AuditEventLogResponseDTO audit) {
        return AccessLogEntryDTO.builder()
                .actor(audit.getUserName())
                .eventType(audit.getEventType())
                .entityType(audit.getEntityType())
                .resourceId(audit.getResourceId())
                .description(audit.getEventDescription())
                .status(audit.getStatus())
                .timestamp(audit.getEventTimestamp())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════
    // PHASE 3 — Proxy / Family Access
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<ProxyResponseDTO> getMyProxies(Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        return patientProxyRepository.findByGrantorPatient_IdAndStatus(patientId, ProxyStatus.ACTIVE)
                .stream().map(this::toProxyResponseDTO).toList();
    }

    @Override
    @Transactional
    public ProxyResponseDTO grantProxy(Authentication auth, ProxyGrantRequestDTO dto) {
        Patient patient = findPatient(auth);

        User proxyUser = userRepository.findByUsername(dto.getProxyUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + dto.getProxyUsername()));

        // Prevent granting proxy to yourself
        if (patient.getUser() != null && patient.getUser().getId().equals(proxyUser.getId())) {
            throw new BusinessException("You cannot grant proxy access to yourself");
        }

        // Prevent duplicate active proxy
        patientProxyRepository.findByGrantorPatient_IdAndProxyUser_IdAndStatus(
                patient.getId(), proxyUser.getId(), ProxyStatus.ACTIVE
        ).ifPresent(existing -> {
            throw new BusinessException("An active proxy already exists for this user");
        });

        PatientProxy proxy = PatientProxy.builder()
                .grantorPatient(patient)
                .proxyUser(proxyUser)
                .relationship(dto.getRelationship())
                .status(ProxyStatus.ACTIVE)
                .permissions(dto.getPermissions())
                .expiresAt(dto.getExpiresAt())
                .notes(dto.getNotes())
                .build();

        return toProxyResponseDTO(patientProxyRepository.save(proxy));
    }

    @Override
    @Transactional
    public void revokeProxy(Authentication auth, UUID proxyId) {
        UUID patientId = resolvePatientId(auth);
        PatientProxy proxy = patientProxyRepository.findById(proxyId)
                .orElseThrow(() -> new ResourceNotFoundException("Proxy grant not found"));

        if (!proxy.getGrantorPatient().getId().equals(patientId)) {
            throw new AccessDeniedException("You can only revoke proxies you have granted");
        }

        proxy.setStatus(ProxyStatus.REVOKED);
        proxy.setRevokedAt(java.time.LocalDateTime.now());
        patientProxyRepository.save(proxy);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProxyResponseDTO> getMyProxyAccess(Authentication auth) {
        UUID userId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException(MSG_UNABLE_RESOLVE_USER));
        return patientProxyRepository.findByProxyUser_IdAndStatus(userId, ProxyStatus.ACTIVE)
                .stream().map(this::toProxyResponseDTO).toList();
    }

    private ProxyResponseDTO toProxyResponseDTO(PatientProxy p) {
        Patient grantor = p.getGrantorPatient();
        User proxy = p.getProxyUser();
        return ProxyResponseDTO.builder()
                .id(p.getId())
                .grantorPatientId(grantor.getId())
                .grantorName(grantor.getFirstName() + " " + grantor.getLastName())
                .proxyUserId(proxy.getId())
                .proxyUsername(proxy.getUsername())
                .proxyDisplayName(proxy.getFirstName() + " " + proxy.getLastName())
                .relationship(p.getRelationship())
                .status(p.getStatus())
                .permissions(p.getPermissions())
                .expiresAt(p.getExpiresAt())
                .revokedAt(p.getRevokedAt())
                .notes(p.getNotes())
                .createdAt(p.getCreatedAt())
                .build();
    }

    // ── Email notification helpers ───────────────────────────────────────

    private void sendCancellationEmail(Appointment appointment) {
        try {
            Patient patient = appointment.getPatient();
            Staff staff = appointment.getStaff();
            Hospital hospital = appointment.getHospital();
            String patientName = patient.getFirstName() + " " + patient.getLastName();
            String staffName = staff.getUser().getFirstName() + " " + staff.getUser().getLastName();
            String appointmentDate = appointment.getAppointmentDate().toString();
            String appointmentTime = appointment.getStartTime() + " - " + appointment.getEndTime();

            emailService.sendAppointmentCancelledEmail(
                    patient.getEmail(), patientName, hospital.getName(), staffName,
                    appointmentDate, appointmentTime,
                    hospital.getEmail(), hospital.getPhoneNumber());

            log.info("Cancellation email sent to {} for appointment {}", patient.getEmail(), appointment.getId());
        } catch (Exception e) {
            log.warn("Failed to send cancellation email for appointment {}: {}", appointment.getId(), e.getMessage());
        }
    }

    private void sendRescheduleEmail(Appointment appointment) {
        try {
            Patient patient = appointment.getPatient();
            Staff staff = appointment.getStaff();
            Hospital hospital = appointment.getHospital();
            String patientName = patient.getFirstName() + " " + patient.getLastName();
            String staffName = staff.getUser().getFirstName() + " " + staff.getUser().getLastName();
            String newAppointmentDate = appointment.getAppointmentDate().toString();
            String newAppointmentTime = appointment.getStartTime() + " - " + appointment.getEndTime();
            String rescheduleLink = frontendBaseUrl + RESCHEDULE_PATH + appointment.getId();
            String cancelLink = frontendBaseUrl + CANCEL_PATH + appointment.getId();

            emailService.sendAppointmentRescheduledEmail(
                    patient.getEmail(), patientName, hospital.getName(), staffName,
                    newAppointmentDate, newAppointmentTime,
                    hospital.getEmail(), hospital.getPhoneNumber(),
                    rescheduleLink, cancelLink);

            log.info("Reschedule email sent to {} for appointment {}", patient.getEmail(), appointment.getId());
        } catch (Exception e) {
            log.warn("Failed to send reschedule email for appointment {}: {}", appointment.getId(), e.getMessage());
        }
    }
}
