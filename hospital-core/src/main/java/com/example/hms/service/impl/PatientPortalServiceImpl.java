package com.example.hms.service.impl;

import com.example.hms.enums.NotificationChannel;
import com.example.hms.enums.NotificationType;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.ProxyStatus;
import com.example.hms.enums.QuestionnaireStatus;
import com.example.hms.enums.RefillStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.QuestionnaireMapper;
import com.example.hms.model.Appointment;
import com.example.hms.model.Patient;
import com.example.hms.model.NotificationPreference;
import com.example.hms.model.PatientProxy;
import com.example.hms.model.Prescription;
import com.example.hms.model.RefillRequest;
import com.example.hms.model.questionnaire.PreVisitQuestionnaire;
import com.example.hms.model.questionnaire.QuestionnaireResponse;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AppointmentResponseDTO;
import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.payload.dto.AppointmentSummaryDTO;
import com.example.hms.payload.dto.DepartmentMinimalDTO;
import com.example.hms.payload.dto.DepartmentWithStaffDTO;
import com.example.hms.payload.dto.StaffMinimalDTO;
import com.example.hms.payload.dto.questionnaire.PreVisitQuestionnaireDTO;
import com.example.hms.payload.dto.questionnaire.QuestionnaireResponseDTO;
import com.example.hms.payload.dto.questionnaire.QuestionnaireResponseSubmitDTO;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.BillingInvoiceResponseDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.PatientConsentRequestDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PatientPrimaryCareResponseDTO;
import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO;
import com.example.hms.payload.dto.medication.PharmacyFillResponseDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.clinical.treatment.TreatmentPlanResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.payload.dto.discharge.DischargeSummaryResponseDTO;
import com.example.hms.payload.dto.lab.PatientLabResultResponseDTO;
import com.example.hms.payload.dto.medication.PatientMedicationResponseDTO;
import com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceDTO;
import com.example.hms.payload.dto.portal.NotificationPreferenceUpdateDTO;
import com.example.hms.payload.dto.portal.AccessLogEntryDTO;
import com.example.hms.payload.dto.portal.CancelAppointmentRequestDTO;
import com.example.hms.payload.dto.portal.CareTeamDTO;
import com.example.hms.payload.dto.portal.CareTeamContactDTO;
import com.example.hms.payload.dto.portal.HealthSummaryDTO;
import com.example.hms.payload.dto.portal.HomeVitalReadingDTO;
import com.example.hms.payload.dto.portal.MedicationRefillRequestDTO;
import com.example.hms.payload.dto.portal.MedicationRefillResponseDTO;
import com.example.hms.payload.dto.portal.PatientProfileDTO;
import com.example.hms.payload.dto.portal.PatientProfileUpdateDTO;
import com.example.hms.payload.dto.portal.PortalConsentRequestDTO;
import com.example.hms.payload.dto.portal.PortalAppointmentRequestDTO;
import com.example.hms.payload.dto.portal.ProxyGrantRequestDTO;
import com.example.hms.payload.dto.portal.ProxyResponseDTO;
import com.example.hms.payload.dto.portal.RescheduleAppointmentRequestDTO;
import com.example.hms.repository.NotificationPreferenceRepository;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProxyRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PharmacyFillRepository;
import com.example.hms.repository.PreVisitQuestionnaireRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.QuestionnaireResponseRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.hms.service.AppointmentService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.BillingInvoiceService;
import com.example.hms.service.ConsultationService;
import com.example.hms.service.DischargeSummaryService;
import com.example.hms.service.EncounterService;
import com.example.hms.service.GeneralReferralService;
import com.example.hms.service.ImmunizationService;
import com.example.hms.service.ImagingOrderService;
import com.example.hms.service.AdmissionService;
import com.example.hms.service.LabOrderService;
import com.example.hms.service.MedicationHistoryService;
import com.example.hms.service.PatientEducationService;
import com.example.hms.service.ProcedureOrderService;
import com.example.hms.service.PatientConsentService;
import com.example.hms.service.PatientLabResultService;
import com.example.hms.service.PatientMedicationService;
import com.example.hms.service.PatientPortalService;
import com.example.hms.service.PatientPrimaryCareService;
import com.example.hms.service.PatientVitalSignService;
import com.example.hms.service.PrescriptionService;
import com.example.hms.service.TreatmentPlanService;
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
    private final NotificationPreferenceRepository notificationPreferenceRepository;

    // Feature 4/5/6 additions
    private final LabOrderService labOrderService;
    private final ImagingOrderService imagingOrderService;
    private final PharmacyFillRepository pharmacyFillRepository;
    private final com.example.hms.mapper.PharmacyFillMapper pharmacyFillMapper;
    // Feature 7/8/9 additions
    private final ProcedureOrderService procedureOrderService;
    private final AdmissionService admissionService;
    private final PatientEducationService patientEducationService;
    // Feature 10/11/12/13 additions
    private final com.example.hms.service.PatientRecordSharingService recordSharingService;
    // Feature 14 — appointment booking
    private final com.example.hms.service.DepartmentService departmentService;
    // Feature 15 — pre-visit questionnaires
    private final PreVisitQuestionnaireRepository preVisitQuestionnaireRepository;
    private final QuestionnaireResponseRepository questionnaireResponseRepository;
    private final QuestionnaireMapper questionnaireMapper;
    private final ObjectMapper objectMapper;
    // Features 16/17/18 — OpenNotes, Post-Visit Instructions, Immunization Certificate
    private final com.example.hms.repository.EncounterNoteRepository encounterNoteRepository;
    private final com.example.hms.mapper.EncounterMapper encounterMapper;
    private final com.example.hms.service.ImmunizationCertificatePdfService immunizationCertificatePdfService;
    // Feature 19 — Health Maintenance Reminders
    private final com.example.hms.repository.HealthMaintenanceReminderRepository healthMaintenanceReminderRepository;
    // Feature 20 — Treatment Progress Tracker
    private final com.example.hms.repository.TreatmentProgressEntryRepository treatmentProgressEntryRepository;
    // Feature 21 — Patient-Reported Outcomes
    private final com.example.hms.repository.PatientReportedOutcomeRepository patientReportedOutcomeRepository;

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

    @Override
    @Transactional(readOnly = true)
    public List<CareTeamContactDTO> getMessageableCareTeam(Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        java.util.ArrayList<PatientPrimaryCareResponseDTO> combined = new java.util.ArrayList<>();
        primaryCareService.getCurrentPrimaryCare(patientId).ifPresent(combined::add);
        primaryCareService.getPrimaryCareHistory(patientId).stream().limit(3).forEach(combined::add);
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        return combined.stream()
                .filter(p -> p.getDoctorUserId() != null && seen.add(p.getDoctorUserId()))
                .map(p -> CareTeamContactDTO.builder()
                        .userId(p.getDoctorUserId())
                        .displayName(p.getDoctorDisplay() != null ? p.getDoctorDisplay() : "Your Provider")
                        .roleLabel(p.isCurrent() ? "Primary Care Provider" : "Previous Provider")
                        .build())
                .toList();
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

    /** Map a NotificationPreference entity to its response DTO. */
    private NotificationPreferenceDTO toNotificationPreferenceDTO(NotificationPreference p) {
        return NotificationPreferenceDTO.builder()
                .id(p.getId())
                .notificationType(p.getNotificationType())
                .channel(p.getChannel())
                .enabled(p.isEnabled())
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 1 — Notification Preferences
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<NotificationPreferenceDTO> getMyNotificationPreferences(Authentication auth) {
        UUID userId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException(MSG_UNABLE_RESOLVE_USER));
        return notificationPreferenceRepository.findByUser_Id(userId)
                .stream()
                .map(this::toNotificationPreferenceDTO)
                .toList();
    }

    @Override
    @Transactional
    public NotificationPreferenceDTO setMyNotificationPreference(Authentication auth,
                                                                  NotificationPreferenceUpdateDTO dto) {
        UUID userId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException(MSG_UNABLE_RESOLVE_USER));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Upsert: find existing row for (user, type, channel) or create new
        NotificationPreference pref = notificationPreferenceRepository.findByUser_Id(userId)
                .stream()
                .filter(p -> p.getNotificationType() == dto.getNotificationType()
                          && p.getChannel() == dto.getChannel())
                .findFirst()
                .orElseGet(() -> NotificationPreference.builder()
                        .user(user)
                        .notificationType(dto.getNotificationType())
                        .channel(dto.getChannel())
                        .build());

        pref.setEnabled(dto.isEnabled());
        pref = notificationPreferenceRepository.save(pref);
        log.info("User {} set notification pref {}/{} = {}", userId,
                dto.getNotificationType(), dto.getChannel(), dto.isEnabled());
        return toNotificationPreferenceDTO(pref);
    }

    @Override
    @Transactional
    public void resetMyNotificationPreferences(Authentication auth) {
        UUID userId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException(MSG_UNABLE_RESOLVE_USER));
        notificationPreferenceRepository.deleteByUser_Id(userId);
        log.info("User {} reset all notification preferences", userId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 2 — Vital Sign Trends
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<PatientVitalSignResponseDTO> getMyVitalTrends(Authentication auth, int months) {
        UUID patientId = resolvePatientId(auth);
        int safeMonths = Math.min(Math.max(months, 1), 24); // clamp 1-24
        java.time.LocalDateTime from = java.time.LocalDateTime.now().minusMonths(safeMonths);
        java.time.LocalDateTime to   = java.time.LocalDateTime.now();
        return vitalSignService.getVitals(patientId, null, from, to, 0, 500);
    }

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 3 — Upcoming Vaccinations
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO> getMyUpcomingVaccinations(
            Authentication auth, int months) {
        UUID patientId = resolvePatientId(auth);
        int safeMonths = Math.min(Math.max(months, 1), 12); // clamp 1-12
        java.time.LocalDate start = java.time.LocalDate.now();
        java.time.LocalDate end   = start.plusMonths(safeMonths);
        return immunizationService.getUpcomingImmunizations(patientId, start, end);
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

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 4 — Lab Orders (status tracking)
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<LabOrderResponseDTO> getMyLabOrders(Authentication auth, java.util.Locale locale) {
        UUID patientId = resolvePatientId(auth);
        return labOrderService.getLabOrdersByPatientId(patientId, locale);
    }

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 5 — Imaging Orders + Results
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<ImagingOrderResponseDTO> getMyImagingOrders(Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        // null status = all statuses
        return imagingOrderService.getOrdersByPatient(patientId, null);
    }

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 6 — Pharmacy Fill History
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<PharmacyFillResponseDTO> getMyPharmacyFills(Authentication auth, java.util.Locale locale) {
        UUID patientId = resolvePatientId(auth);
        return pharmacyFillRepository.findByPatient_IdOrderByFillDateDesc(patientId)
                .stream()
                .map(pharmacyFillMapper::toResponseDTO)
                .toList();
    }

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 7 — Procedure Orders
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.procedure.ProcedureOrderResponseDTO> getMyProcedureOrders(Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        return procedureOrderService.getProcedureOrdersForPatient(patientId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 8 — Admissions / Hospitalization History
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.AdmissionResponseDTO> getMyAdmissions(Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        return admissionService.getAdmissionsByPatient(patientId);
    }

    @Override
    @Transactional(readOnly = true)
    public com.example.hms.payload.dto.AdmissionResponseDTO getMyCurrentAdmission(Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        return admissionService.getCurrentAdmissionForPatient(patientId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 9 — Patient Education Progress
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.education.PatientEducationProgressResponseDTO> getMyEducationProgress(Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        return patientEducationService.getPatientProgress(patientId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.education.PatientEducationProgressResponseDTO> getMyInProgressEducation(Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        return patientEducationService.getInProgressResources(patientId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.education.PatientEducationProgressResponseDTO> getMyCompletedEducation(Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        return patientEducationService.getCompletedResources(patientId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 10 — Browse Education Resources
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.education.EducationResourceResponseDTO> getMyEducationResources(Authentication auth) {
        Patient patient = findPatient(auth);
        UUID hospitalId = resolvePatientHospitalId(patient);
        if (hospitalId == null) {
            return Collections.emptyList();
        }
        return patientEducationService.getAllResources(hospitalId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.education.EducationResourceResponseDTO> searchMyEducationResources(Authentication auth, String query) {
        Patient patient = findPatient(auth);
        UUID hospitalId = resolvePatientHospitalId(patient);
        if (hospitalId == null) {
            return Collections.emptyList();
        }
        return patientEducationService.searchResources(query, hospitalId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.education.EducationResourceResponseDTO> getMyEducationResourcesByCategory(
            Authentication auth, com.example.hms.enums.EducationCategory category) {
        Patient patient = findPatient(auth);
        UUID hospitalId = resolvePatientHospitalId(patient);
        if (hospitalId == null) {
            return Collections.emptyList();
        }
        return patientEducationService.getResourcesByCategory(category, hospitalId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 11 — Medical Records Self-Download
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public byte[] downloadMyRecord(Authentication auth, String format) {
        UUID patientId = resolvePatientId(auth);
        return recordSharingService.exportSelfRecord(patientId, format);
    }

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 12 — Lab Result Trends
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.lab.LabResultTrendDTO> getMyLabResultTrends(Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        return labResultService.getLabResultTrends(patientId);
    }

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 13 — Online Check-In
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public com.example.hms.payload.dto.AppointmentResponseDTO checkInMyAppointment(
            Authentication auth, UUID appointmentId, Locale locale) {
        UUID patientId = resolvePatientId(auth);

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));

        requirePatientOwnership(appointment, patientId);

        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new BusinessException("Cannot check in to a cancelled appointment");
        }
        if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
            throw new BusinessException("Cannot check in to a completed appointment");
        }
        if (appointment.getStatus() == AppointmentStatus.CHECKED_IN) {
            throw new BusinessException("You have already checked in to this appointment");
        }

        appointment.setStatus(AppointmentStatus.CHECKED_IN);
        appointmentRepository.save(appointment);
        log.info("Patient {} checked in to appointment {}", patientId, appointmentId);
        return appointmentMapper.toAppointmentResponseDTO(appointment);
    }

    // ══════════════════════════════════════════════════════════════════════
    // FEATURE 14 — Appointment Booking
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<DepartmentMinimalDTO> getMyDepartments(Authentication auth, Locale locale) {
        Patient patient = findPatient(auth);
        UUID hospitalId = resolvePatientHospitalId(patient);
        if (hospitalId == null) {
            return Collections.emptyList();
        }
        return departmentService.getActiveDepartmentsMinimal(hospitalId, locale);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffMinimalDTO> getDepartmentProviders(Authentication auth, UUID departmentId, Locale locale) {
        DepartmentWithStaffDTO dept = departmentService.getDepartmentWithStaff(departmentId, locale);
        return dept.getStaffMembers() != null ? dept.getStaffMembers() : Collections.emptyList();
    }

    @Override
    @Transactional
    public AppointmentSummaryDTO bookMyAppointment(
            Authentication auth, PortalAppointmentRequestDTO dto, Locale locale) {
        Patient patient = findPatient(auth);
        UUID hospitalId = resolvePatientHospitalId(patient);

        AppointmentRequestDTO request = AppointmentRequestDTO.builder()
                .patientId(patient.getId())
                .hospitalId(hospitalId)
                .departmentId(dto.getDepartmentId())
                .staffId(dto.getStaffId())
                .appointmentDate(dto.getAppointmentDate())
                .startTime(dto.getStartTime())
                .endTime(dto.getEndTime())
                .reason(dto.getReason())
                .notes(dto.getNotes())
                .status(AppointmentStatus.PENDING)
                .build();

        String username = auth.getName();
        AppointmentSummaryDTO created = appointmentService.createAppointment(request, locale, username);
        log.info("Patient {} booked appointment {}", patient.getId(), created.getId());
        return created;
    }

    // ════════════════════════════════════════════════════════════════════
    // FEATURE 15 — Pre-Visit Questionnaires
    // ════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<PreVisitQuestionnaireDTO> getMyPendingQuestionnaires(Authentication auth) {
        Patient patient = findPatient(auth);
        UUID hospitalId = resolvePatientHospitalId(patient);
        if (hospitalId == null) {
            return Collections.emptyList();
        }
        UUID patientId = patient.getId();
        return preVisitQuestionnaireRepository.findByHospitalIdAndActiveTrue(hospitalId)
                .stream()
                .filter(q -> !questionnaireResponseRepository
                        .existsByPatientIdAndQuestionnaireId(patientId, q.getId()))
                .map(questionnaireMapper::toPreVisitQuestionnaireDTO)
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuestionnaireResponseDTO> getMySubmittedQuestionnaires(Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        return questionnaireResponseRepository.findByPatientId(patientId)
                .stream()
                .map(questionnaireMapper::toQuestionnaireResponseDTO)
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    @Transactional
    public QuestionnaireResponseDTO submitMyQuestionnaire(
            Authentication auth, QuestionnaireResponseSubmitDTO dto) {
        Patient patient = findPatient(auth);
        UUID patientId = patient.getId();
        UUID hospitalId = resolvePatientHospitalId(patient);

        if (questionnaireResponseRepository.existsByPatientIdAndQuestionnaireId(
                patientId, dto.getQuestionnaireId())) {
            throw new BusinessException("You have already submitted a response for this questionnaire");
        }

        PreVisitQuestionnaire questionnaire = preVisitQuestionnaireRepository
                .findById(dto.getQuestionnaireId())
                .orElseThrow(() -> new ResourceNotFoundException("Questionnaire not found"));

        if (!questionnaire.getHospitalId().equals(hospitalId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Questionnaire does not belong to your hospital");
        }

        String answersJson;
        try {
            answersJson = objectMapper.writeValueAsString(dto.getAnswers());
        } catch (JsonProcessingException e) {
            throw new BusinessException("Failed to serialize answers");
        }

        QuestionnaireResponse response = QuestionnaireResponse.builder()
                .patientId(patientId)
                .hospitalId(hospitalId)
                .questionnaireId(dto.getQuestionnaireId())
                .appointmentId(dto.getAppointmentId())
                .answersJson(answersJson)
                .status(QuestionnaireStatus.SUBMITTED)
                .submittedAt(java.time.LocalDateTime.now())
                .questionnaireTitle(questionnaire.getTitle())
                .build();

        response = questionnaireResponseRepository.save(response);
        log.info("Patient {} submitted questionnaire {}", patientId, dto.getQuestionnaireId());
        return questionnaireMapper.toQuestionnaireResponseDTO(response);
    }

    // ════════════════════════════════════════════════════════════════════
    // FEATURE 16 — OpenNotes (Visit Notes)
    // ════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public com.example.hms.payload.dto.EncounterNoteResponseDTO getMyEncounterNote(
            Authentication auth, UUID encounterId) {
        UUID patientId = resolvePatientId(auth);
        com.example.hms.model.encounter.EncounterNote note =
                encounterNoteRepository.findByEncounter_Id(encounterId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "No clinical note found for this visit"));
        if (!note.getPatient().getId().equals(patientId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "This visit note does not belong to your account");
        }
        return encounterMapper.toEncounterNoteResponseDTO(note);
    }

    // ════════════════════════════════════════════════════════════════════
    // FEATURE 17 — Post-Visit Instructions
    // ════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public com.example.hms.payload.dto.portal.PortalDischargeInstructionsDTO getMyPostVisitInstructions(
            Authentication auth, UUID encounterId, Locale locale) {
        UUID patientId = resolvePatientId(auth);
        DischargeSummaryResponseDTO summary;
        try {
            summary = dischargeSummaryService.getDischargeSummaryByEncounter(encounterId, locale);
        } catch (ResourceNotFoundException e) {
            throw new ResourceNotFoundException("No discharge instructions found for this visit");
        }
        if (!patientId.equals(summary.getPatientId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "These instructions do not belong to your account");
        }
        return com.example.hms.payload.dto.portal.PortalDischargeInstructionsDTO.builder()
                .id(summary.getId())
                .encounterId(summary.getEncounterId())
                .dischargeDate(summary.getDischargeDate())
                .disposition(summary.getDisposition() != null ? summary.getDisposition().name() : null)
                .dischargeDiagnosis(summary.getDischargeDiagnosis())
                .hospitalCourse(summary.getHospitalCourse())
                .dischargeCondition(summary.getDischargeCondition())
                .activityRestrictions(summary.getActivityRestrictions())
                .dietInstructions(summary.getDietInstructions())
                .woundCareInstructions(summary.getWoundCareInstructions())
                .followUpInstructions(summary.getFollowUpInstructions())
                .warningSigns(summary.getWarningSigns())
                .patientEducationProvided(summary.getPatientEducationProvided())
                .equipmentAndSupplies(summary.getEquipmentAndSupplies())
                .isFinalized(summary.getIsFinalized())
                .finalizedAt(summary.getFinalizedAt())
                .build();
    }

    // ════════════════════════════════════════════════════════════════════
    // FEATURE 18 — Immunization Certificate
    // ════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public byte[] generateMyImmunizationCertificate(Authentication auth) {
        Patient patient = findPatient(auth);
        List<com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO> immunizations =
                immunizationService.getImmunizationsByPatientId(patient.getId());
        String patientName = patient.getFullName();
        return immunizationCertificatePdfService.generate(patientName, immunizations);
    }

    // ════════════════════════════════════════════════════════════════════
    // FEATURE 19 — Health Maintenance Reminders
    // ════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.portal.PortalHealthReminderDTO> getMyHealthReminders(
            Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        return healthMaintenanceReminderRepository.findByPatientIdAndActiveTrue(patientId)
                .stream()
                .map(this::toHealthReminderDTO)
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    @Transactional
    public com.example.hms.payload.dto.portal.PortalHealthReminderDTO completeMyHealthReminder(
            Authentication auth, UUID reminderId) {
        UUID patientId = resolvePatientId(auth);
        com.example.hms.model.HealthMaintenanceReminder reminder =
                healthMaintenanceReminderRepository.findById(reminderId)
                        .orElseThrow(() -> new ResourceNotFoundException("Reminder not found"));
        if (!reminder.getPatientId().equals(patientId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "This reminder does not belong to your account");
        }
        reminder.setStatus(com.example.hms.enums.HealthMaintenanceReminderStatus.COMPLETED);
        reminder.setCompletedDate(java.time.LocalDate.now());
        reminder = healthMaintenanceReminderRepository.save(reminder);
        log.info("Patient {} marked reminder {} as completed", patientId, reminderId);
        return toHealthReminderDTO(reminder);
    }

    private com.example.hms.payload.dto.portal.PortalHealthReminderDTO toHealthReminderDTO(
            com.example.hms.model.HealthMaintenanceReminder r) {
        String typeLabel = r.getType() != null
                ? r.getType().name().replace("_", " ") : "";
        boolean overdue = r.getStatus() == com.example.hms.enums.HealthMaintenanceReminderStatus.OVERDUE
                || (r.getDueDate() != null
                    && java.time.LocalDate.now().isAfter(r.getDueDate())
                    && r.getStatus() == com.example.hms.enums.HealthMaintenanceReminderStatus.PENDING);
        return com.example.hms.payload.dto.portal.PortalHealthReminderDTO.builder()
                .id(r.getId())
                .type(r.getType() != null ? r.getType().name() : null)
                .typeLabel(typeLabel)
                .dueDate(r.getDueDate())
                .status(r.getStatus() != null ? r.getStatus().name() : null)
                .notes(r.getNotes())
                .completedDate(r.getCompletedDate())
                .completedBy(r.getCompletedBy())
                .overdue(overdue)
                .createdAt(r.getCreatedAt())
                .build();
    }

    // ════════════════════════════════════════════════════════════════════
    // FEATURE 20 — Treatment Progress Tracker
    // ════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.portal.PortalProgressEntryDTO> getMyTreatmentPlanProgress(
            Authentication auth, UUID planId) {
        UUID patientId = resolvePatientId(auth);
        // Ownership check: the plan must belong to this patient
        TreatmentPlanResponseDTO plan = treatmentPlanService.getById(planId);
        if (!plan.getPatientId().equals(patientId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "This treatment plan does not belong to your account");
        }
        return treatmentProgressEntryRepository
                .findByTreatmentPlanIdOrderByProgressDateDesc(planId)
                .stream()
                .map(this::toProgressEntryDTO)
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    @Transactional
    public com.example.hms.payload.dto.portal.PortalProgressEntryDTO logMyTreatmentProgress(
            Authentication auth, UUID planId,
            com.example.hms.payload.dto.portal.PortalProgressEntryRequestDTO request) {
        UUID patientId = resolvePatientId(auth);
        TreatmentPlanResponseDTO plan = treatmentPlanService.getById(planId);
        if (!plan.getPatientId().equals(patientId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "This treatment plan does not belong to your account");
        }
        com.example.hms.model.TreatmentProgressEntry entry =
                com.example.hms.model.TreatmentProgressEntry.builder()
                        .treatmentPlanId(planId)
                        .patientId(patientId)
                        .progressDate(request.getProgressDate() != null
                                ? request.getProgressDate() : java.time.LocalDate.now())
                        .progressNote(request.getProgressNote())
                        .selfRating(request.getSelfRating())
                        .onTrack(request.getOnTrack() != null ? request.getOnTrack() : true)
                        .build();
        entry = treatmentProgressEntryRepository.save(entry);
        log.info("Patient {} logged progress entry {} for plan {}", patientId, entry.getId(), planId);
        return toProgressEntryDTO(entry);
    }

    private com.example.hms.payload.dto.portal.PortalProgressEntryDTO toProgressEntryDTO(
            com.example.hms.model.TreatmentProgressEntry e) {
        return com.example.hms.payload.dto.portal.PortalProgressEntryDTO.builder()
                .id(e.getId())
                .treatmentPlanId(e.getTreatmentPlanId())
                .progressDate(e.getProgressDate())
                .progressNote(e.getProgressNote())
                .selfRating(e.getSelfRating())
                .onTrack(e.getOnTrack())
                .createdAt(e.getCreatedAt())
                .build();
    }

    // ════════════════════════════════════════════════════════════════════
    // FEATURE 21 — Patient-Reported Outcomes (PROs)
    // ════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.portal.PortalOutcomeDTO> getMyOutcomes(
            Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        return patientReportedOutcomeRepository
                .findByPatientIdOrderByReportDateDesc(patientId)
                .stream()
                .map(this::toOutcomeDTO)
                .collect(java.util.stream.Collectors.toList());
    }

    @Override
    @Transactional
    public com.example.hms.payload.dto.portal.PortalOutcomeDTO reportMyOutcome(
            Authentication auth,
            com.example.hms.payload.dto.portal.PortalOutcomeRequestDTO request) {
        Patient patient = findPatient(auth);
        com.example.hms.model.PatientReportedOutcome outcome =
                com.example.hms.model.PatientReportedOutcome.builder()
                        .patientId(patient.getId())
                        .hospitalId(patient.getHospitalId())
                        .outcomeType(request.getOutcomeType())
                        .score(request.getScore())
                        .notes(request.getNotes())
                        .reportDate(request.getReportDate() != null
                                ? request.getReportDate() : java.time.LocalDate.now())
                        .encounterId(request.getEncounterId())
                        .build();
        outcome = patientReportedOutcomeRepository.save(outcome);
        log.info("Patient {} reported outcome {} (score={})",
                patient.getId(), outcome.getOutcomeType(), outcome.getScore());
        return toOutcomeDTO(outcome);
    }

    private com.example.hms.payload.dto.portal.PortalOutcomeDTO toOutcomeDTO(
            com.example.hms.model.PatientReportedOutcome o) {
        String typeLabel = o.getOutcomeType() != null ? o.getOutcomeType().getLabel() : "";
        return com.example.hms.payload.dto.portal.PortalOutcomeDTO.builder()
                .id(o.getId())
                .outcomeType(o.getOutcomeType())
                .typeLabel(typeLabel)
                .score(o.getScore())
                .notes(o.getNotes())
                .reportDate(o.getReportDate())
                .encounterId(o.getEncounterId())
                .createdAt(o.getCreatedAt())
                .build();
    }
}
