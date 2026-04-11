package com.example.hms.service.impl;

import com.example.hms.config.SecurityConstants;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.ProxyStatus;
import com.example.hms.enums.RefillStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Appointment;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientProxy;
import com.example.hms.model.Prescription;
import com.example.hms.model.RefillRequest;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
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
import com.example.hms.payload.dto.portal.PortalBookAppointmentRequestDTO;
import com.example.hms.payload.dto.portal.PortalConsentRequestDTO;
import com.example.hms.payload.dto.portal.ProxyGrantRequestDTO;
import com.example.hms.payload.dto.portal.ProxyResponseDTO;
import com.example.hms.payload.dto.portal.RescheduleAppointmentRequestDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProxyRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.QuestionnaireRepository;
import com.example.hms.repository.QuestionnaireResponseRepository;
import com.example.hms.service.AppointmentService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.mapper.QuestionnaireMapper;
import com.example.hms.model.Questionnaire;
import com.example.hms.model.QuestionnaireResponse;
import com.example.hms.payload.dto.portal.QuestionnaireDTO;
import com.example.hms.payload.dto.portal.QuestionnaireSubmissionDTO;
import com.example.hms.payload.dto.portal.PreCheckInRequestDTO;
import com.example.hms.payload.dto.portal.PreCheckInResponseDTO;
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
import com.example.hms.service.StaffAvailabilityService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final String MEDICATION_REFILL_NOTIFICATION_TYPE = "MEDICATION_REFILL";

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
    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;
    private final StaffRepository staffRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final StaffAvailabilityService staffAvailabilityService;
    private final com.example.hms.repository.UserRepository userRepository;
    private final com.example.hms.service.NotificationService notificationService;
    private final EmailService emailService;

    // MVP 4 additions
    private final QuestionnaireRepository questionnaireRepository;
    private final QuestionnaireResponseRepository questionnaireResponseRepository;
    private final QuestionnaireMapper questionnaireMapper;

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

    // ── Schedule own appointment ─────────────────────────────────────────

    @Override
    @Transactional
    public AppointmentResponseDTO scheduleMyAppointment(Authentication auth,
                                                        PortalBookAppointmentRequestDTO dto,
                                                        Locale locale) {
        UUID patientId = resolvePatientId(auth);
        Patient patientEntity = patientRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        // Verify patient is registered at this hospital
        requireHospitalRegistration(patientId, dto.getHospitalId());

        Hospital hospital = hospitalRepository.findById(dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        Department department = departmentRepository.findById(dto.getDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Department not found"));
        if (!department.getHospital().getId().equals(dto.getHospitalId())) {
            throw new BusinessException("Department does not belong to the selected hospital");
        }

        // Resolve staff — explicit pick or first available in department
        Staff staff;
        if (dto.getStaffId() != null) {
            staff = staffRepository.findByIdAndActiveTrue(dto.getStaffId())
                    .orElseThrow(() -> new ResourceNotFoundException("Provider not found"));
            if (!staff.getHospital().getId().equals(dto.getHospitalId())) {
                throw new BusinessException("Provider does not belong to the selected hospital");
            }
        } else {
            List<Staff> available = staffRepository.findActiveProvidersByHospitalAndDepartment(
                    dto.getHospitalId(), dto.getDepartmentId());
            if (available.isEmpty()) {
                throw new BusinessException("No providers available in the selected department");
            }
            staff = available.get(0); // assign first available
        }

        // Time defaults — endTime = startTime + 30 min if omitted
        java.time.LocalTime endTime = dto.getEndTime() != null
                ? dto.getEndTime()
                : dto.getStartTime().plusMinutes(30);
        java.time.LocalDateTime requestedStart = java.time.LocalDateTime.of(dto.getDate(), dto.getStartTime());
        java.time.LocalDateTime requestedEnd = java.time.LocalDateTime.of(dto.getDate(), endTime);
        if (!requestedEnd.isAfter(requestedStart)) {
            throw new BusinessException("Appointment end time must be after start time");
        }

        // Staff availability check
        if (!staffAvailabilityService.isStaffAvailable(staff.getId(), requestedStart)) {
            throw new BusinessException("The selected provider is not available at the requested time");
        }

        // Overlap check
        boolean hasConflict = appointmentRepository
                .findByStaff_IdAndAppointmentDate(staff.getId(), dto.getDate())
                .stream()
                .anyMatch(existing -> {
                    java.time.LocalDateTime es = java.time.LocalDateTime.of(existing.getAppointmentDate(), existing.getStartTime());
                    java.time.LocalDateTime ee = java.time.LocalDateTime.of(existing.getAppointmentDate(), existing.getEndTime());
                    return requestedStart.isBefore(ee) && es.isBefore(requestedEnd);
                });
        if (hasConflict) {
            throw new BusinessException("The selected provider already has an appointment at the requested time");
        }

        // Resolve the staff's role assignment for this hospital
        UserRoleHospitalAssignment assignment = assignmentRepository
                .findByUserIdAndHospitalId(staff.getUser().getId(), hospital.getId())
                .orElseThrow(() -> new BusinessException("Staff role assignment not found"));

        // Create the appointment
        Appointment appointment = new Appointment();
        appointment.setPatient(patientEntity);
        appointment.setStaff(staff);
        appointment.setHospital(hospital);
        appointment.setDepartment(department);
        appointment.setAppointmentDate(dto.getDate());
        appointment.setStartTime(dto.getStartTime());
        appointment.setEndTime(endTime);
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        appointment.setNotes(dto.getNotes());
        appointment.setCreatedBy(patientEntity.getUser());
        appointment.setAssignment(assignment);

        Appointment saved = appointmentRepository.save(appointment);
        log.info("Patient {} self-scheduled appointment {} at hospital {} department {}",
                patientId, saved.getId(), hospital.getName(), department.getName());

        return appointmentMapper.toAppointmentResponseDTO(saved);
    }

    // ── Booking-form lookups ─────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getMyHospitals(Authentication auth) {
        UUID patientId = resolvePatientId(auth);
        List<PatientHospitalRegistration> registrations =
                registrationRepository.findByPatientId(patientId).stream()
                        .filter(PatientHospitalRegistration::isActive)
                        .toList();
        return registrations.stream()
                .map(r -> {
                    Hospital h = r.getHospital();
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", h.getId());
                    m.put("name", h.getName());
                    m.put("address", h.getAddress());
                    return m;
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getDepartmentsForHospital(UUID hospitalId) {
        return departmentRepository.findByHospitalId(hospitalId).stream()
                .map(d -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", d.getId());
                    m.put("name", d.getName());
                    return m;
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getProvidersForDepartment(UUID hospitalId, UUID departmentId) {
        return staffRepository.findActiveProvidersByHospitalAndDepartment(hospitalId, departmentId).stream()
                .map(s -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("name", s.getName());
                    if (s.getUser() != null) {
                        String fullName = (s.getUser().getFirstName() != null ? s.getUser().getFirstName() : "")
                                + " " + (s.getUser().getLastName() != null ? s.getUser().getLastName() : "");
                        m.put("fullName", fullName.trim());
                    }
                    if (s.getAssignment() != null && s.getAssignment().getRole() != null) {
                        m.put("role", s.getAssignment().getRole().getName());
                    }
                    return m;
                })
                .toList();
    }

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
    notifyCareTeamForRefillRequest(refill);
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
        return dischargeSummaryService.getDischargeSummariesForPortalPatient(patientId);
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
        UUID hospId = resolvePatientHospitalId(p);
        String hospName = null;
        if (hospId != null) {
            hospName = hospitalRepository.findById(hospId)
                    .map(h -> h.getName())
                    .orElse(null);
        }

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
                .profileImageUrl(p.getUser() != null ? p.getUser().getProfileImageUrl() : null)
                .hospitalId(hospId)
                .hospitalName(hospName)
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

    private void notifyCareTeamForRefillRequest(RefillRequest refill) {
        Prescription prescription = refill.getPrescription();
        Patient patient = refill.getPatient();
        if (prescription == null || patient == null) {
            return;
        }

        String medicationName = prescription.getMedicationName() != null && !prescription.getMedicationName().isBlank()
                ? prescription.getMedicationName()
                : "prescription";
        String patientName = ((patient.getFirstName() != null ? patient.getFirstName() : "")
                + " " + (patient.getLastName() != null ? patient.getLastName() : "")).trim();
        if (patientName.isBlank()) {
            patientName = "a patient";
        }

        Set<String> recipientUsernames = new LinkedHashSet<>();
        Set<String> recipientEmails = new LinkedHashSet<>();

        Staff prescriber = prescription.getStaff();
        if (prescriber != null && prescriber.getUser() != null) {
            String username = prescriber.getUser().getUsername();
            if (username != null && !username.isBlank()) {
                recipientUsernames.add(username);
            }
            String email = prescriber.getUser().getEmail();
            if (email != null && !email.isBlank()) {
                recipientEmails.add(email);
            }
        }

        if (prescription.getHospital() != null
                && prescription.getHospital().getId() != null
                && prescriber != null
                && prescriber.getDepartment() != null
                && prescriber.getDepartment().getId() != null) {
            List<Staff> careTeam = staffRepository.findActiveProvidersByHospitalAndDepartment(
                    prescription.getHospital().getId(),
                    prescriber.getDepartment().getId());

            for (Staff staff : careTeam) {
                if (!isDoctorOrNurse(staff) || staff.getUser() == null) {
                    continue;
                }
                String username = staff.getUser().getUsername();
                if (username != null && !username.isBlank()) {
                    recipientUsernames.add(username);
                }
                String email = staff.getUser().getEmail();
                if (email != null && !email.isBlank()) {
                    recipientEmails.add(email);
                }
            }
        }

        String message = "Medication refill request from " + patientName + " for " + medicationName + ".";
        for (String username : recipientUsernames) {
            try {
                notificationService.createNotification(message, username, MEDICATION_REFILL_NOTIFICATION_TYPE);
            } catch (Exception e) {
                log.warn("Failed to deliver refill notification to {} for refill {}", username, refill.getId());
            }
        }

        if (!recipientEmails.isEmpty()) {
            String subject = "Medication Refill Request Pending Review";
            String body = """
                <h2>Medication Refill Request</h2>
                <p><strong>%s</strong> requested a refill for <strong>%s</strong>.</p>
                <p>Please review and respond in your HMS dashboard.</p>
                """.formatted(patientName, medicationName);
            for (String email : recipientEmails) {
                try {
                    emailService.sendHtml(List.of(email), List.of(), List.of(), subject, body);
                } catch (Exception e) {
                    log.warn("Failed to send refill email to {} for refill {}", email, refill.getId());
                }
            }
        }
    }

    private boolean isDoctorOrNurse(Staff staff) {
        if (staff.getAssignment() == null || staff.getAssignment().getRole() == null) {
            return false;
        }
        String roleCode = staff.getAssignment().getRole().getCode();
        return SecurityConstants.ROLE_DOCTOR.equalsIgnoreCase(roleCode)
                || SecurityConstants.ROLE_NURSE.equalsIgnoreCase(roleCode);
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

    // ── Proxy data-viewing implementations ───────────────────────────────

    /**
     * Validate that the authenticated user has an active proxy grant for the
     * given patient with the required permission scope.
     */
    private Patient verifyProxyAccess(Authentication auth, UUID patientId, String requiredPermission) {
        UUID userId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException(MSG_UNABLE_RESOLVE_USER));

        PatientProxy proxy = patientProxyRepository
                .findByGrantorPatient_IdAndProxyUser_IdAndStatus(patientId, userId, ProxyStatus.ACTIVE)
                .orElseThrow(() -> new AccessDeniedException("You do not have proxy access to this patient's data"));

        // Check permission scope
        String perms = proxy.getPermissions() != null ? proxy.getPermissions().toUpperCase() : "";
        if (!perms.contains("ALL") && !perms.contains(requiredPermission.toUpperCase())) {
            throw new AccessDeniedException("Your proxy access does not include " + requiredPermission);
        }

        return patientRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponseDTO> getProxyAppointments(Authentication auth, UUID patientId, Locale locale) {
        Patient patient = verifyProxyAccess(auth, patientId, "VIEW_APPOINTMENTS");
        return appointmentService.getAppointmentsByPatientId(patient.getId(), locale, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientMedicationResponseDTO> getProxyMedications(Authentication auth, UUID patientId, int limit) {
        Patient patient = verifyProxyAccess(auth, patientId, "VIEW_MEDICATIONS");
        UUID hospitalId = resolvePatientHospitalId(patient);
        return medicationService.getMedicationsForPatient(patient.getId(), hospitalId, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientLabResultResponseDTO> getProxyLabResults(Authentication auth, UUID patientId, int limit) {
        Patient patient = verifyProxyAccess(auth, patientId, "VIEW_LAB_RESULTS");
        UUID hospitalId = resolvePatientHospitalId(patient);
        return labResultService.getLabResultsForPatient(patient.getId(), hospitalId, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BillingInvoiceResponseDTO> getProxyBilling(Authentication auth, UUID patientId, Pageable pageable, Locale locale) {
        Patient patient = verifyProxyAccess(auth, patientId, "VIEW_BILLING");
        return billingInvoiceService.getInvoicesByPatientId(patient.getId(), pageable, locale);
    }

    @Override
    @Transactional(readOnly = true)
    public HealthSummaryDTO getProxyRecords(Authentication auth, UUID patientId, Locale locale) {
        Patient patient = verifyProxyAccess(auth, patientId, "VIEW_RECORDS");
        UUID hospitalId = resolvePatientHospitalId(patient);
        return HealthSummaryDTO.builder()
                .profile(toProfileDTO(patient))
                .recentLabResults(safeLabResults(patient.getId(), hospitalId))
                .currentMedications(safeMedications(patient.getId(), hospitalId))
                .recentVitals(safeVitals(patient.getId()))
                .immunizations(safeImmunizations(patient.getId()))
                .allergies(splitToList(patient.getAllergies()))
                .chronicConditions(splitToList(patient.getChronicConditions()))
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

    // ── Phase 3: Notification preferences ───────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<com.example.hms.payload.dto.portal.NotificationPreferenceDTO> getMyNotificationPreferences(Authentication auth) {
        UUID userId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException(MSG_UNABLE_RESOLVE_USER));
        return notificationService.getPreferences(userId);
    }

    @Override
    @Transactional
    public List<com.example.hms.payload.dto.portal.NotificationPreferenceDTO> updateMyNotificationPreferences(
            Authentication auth, List<com.example.hms.payload.dto.portal.NotificationPreferenceUpdateDTO> updates) {
        UUID userId = authUtils.resolveUserId(auth)
                .orElseThrow(() -> new BusinessException(MSG_UNABLE_RESOLVE_USER));
        return notificationService.updatePreferences(userId, updates);
    }

    // ══════════════════════════════════════════════════════════════════════
    // MVP 4 — Pre-Visit Questionnaires & Pre-Check-In
    // ══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true)
    public List<QuestionnaireDTO> getQuestionnairesForAppointment(Authentication auth, UUID appointmentId) {
        UUID patientId = resolvePatientId(auth);
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("appointment.notfound", appointmentId));

        // Ownership check — patient can only see their own appointment's questionnaires
        if (!appointment.getPatient().getId().equals(patientId)) {
            throw new BusinessException("You do not have access to this appointment.");
        }

        // Find active questionnaires for the appointment's hospital + department
        List<Questionnaire> questionnaires;
        if (appointment.getDepartment() != null) {
            questionnaires = questionnaireRepository
                    .findByHospital_IdAndDepartment_IdAndActiveTrue(
                            appointment.getHospital().getId(),
                            appointment.getDepartment().getId());
        } else {
            questionnaires = questionnaireRepository
                    .findByHospital_IdAndActiveTrue(appointment.getHospital().getId());
        }

        // Also include hospital-wide questionnaires (department_id IS NULL)
        if (appointment.getDepartment() != null) {
            List<Questionnaire> hospitalWide = questionnaireRepository
                    .findByHospital_IdAndActiveTrue(appointment.getHospital().getId())
                    .stream()
                    .filter(q -> q.getDepartment() == null)
                    .toList();
            // Merge, avoiding duplicates
            java.util.Set<UUID> ids = questionnaires.stream()
                    .map(Questionnaire::getId)
                    .collect(java.util.stream.Collectors.toSet());
            List<Questionnaire> combined = new java.util.ArrayList<>(questionnaires);
            for (Questionnaire q : hospitalWide) {
                if (!ids.contains(q.getId())) {
                    combined.add(q);
                }
            }
            questionnaires = combined;
        }

        return questionnaires.stream()
                .map(questionnaireMapper::toDto)
                .toList();
    }

    @Override
    @Transactional
    public PreCheckInResponseDTO submitPreCheckIn(Authentication auth, PreCheckInRequestDTO dto) {
        UUID patientId = resolvePatientId(auth);
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("patient.notfound", patientId));

        Appointment appointment = appointmentRepository.findById(dto.getAppointmentId())
                .orElseThrow(() -> new ResourceNotFoundException("appointment.notfound", dto.getAppointmentId()));

        // Ownership check
        if (!appointment.getPatient().getId().equals(patientId)) {
            throw new BusinessException("You do not have access to this appointment.");
        }

        // Only allow pre-check-in for upcoming SCHEDULED/CONFIRMED appointments
        if (appointment.getStatus() != com.example.hms.enums.AppointmentStatus.SCHEDULED
                && appointment.getStatus() != com.example.hms.enums.AppointmentStatus.CONFIRMED) {
            throw new IllegalStateException(
                    "Pre-check-in is only available for SCHEDULED or CONFIRMED appointments. Current: "
                            + appointment.getStatus());
        }

        // Validate timing: 1-7 days before appointment
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate apptDate = appointment.getAppointmentDate();
        long daysUntil = java.time.temporal.ChronoUnit.DAYS.between(today, apptDate);
        if (daysUntil < 0 || daysUntil > 7) {
            throw new BusinessException("Pre-check-in is available 1–7 days before your appointment.");
        }

        // 1) Update demographics if provided
        boolean demographicsUpdated = updatePatientDemographics(patient, dto);

        // 2) Process questionnaire responses
        int qrCount = 0;
        if (dto.getQuestionnaireResponses() != null) {
            for (QuestionnaireSubmissionDTO sub : dto.getQuestionnaireResponses()) {
                Questionnaire questionnaire = questionnaireRepository.findById(sub.getQuestionnaireId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "questionnaire.notfound", sub.getQuestionnaireId()));

                // Idempotency: skip if already submitted
                if (!questionnaireResponseRepository.existsByQuestionnaire_IdAndAppointment_Id(
                        questionnaire.getId(), appointment.getId())) {
                    QuestionnaireResponse qr = QuestionnaireResponse.builder()
                            .questionnaire(questionnaire)
                            .patient(patient)
                            .appointment(appointment)
                            .responses(sub.getResponses())
                            .submittedAt(java.time.LocalDateTime.now())
                            .build();
                    questionnaireResponseRepository.save(qr);
                    qrCount++;
                }
            }
        }

        // 3) Mark appointment as pre-checked-in
        appointment.setPreCheckedIn(true);
        appointment.setPreCheckinTimestamp(java.time.LocalDateTime.now());
        appointmentRepository.save(appointment);

        log.info("Patient {} completed pre-check-in for appointment {}", patientId, appointment.getId());

        return PreCheckInResponseDTO.builder()
                .appointmentId(appointment.getId())
                .appointmentStatus(appointment.getStatus().name())
                .preCheckedIn(true)
                .preCheckinTimestamp(appointment.getPreCheckinTimestamp())
                .questionnaireResponsesSubmitted(qrCount)
                .demographicsUpdated(demographicsUpdated)
                .build();
    }

    /**
     * Apply optional demographics updates from the pre-check-in form to the patient record.
     */
    private boolean updatePatientDemographics(Patient patient, PreCheckInRequestDTO dto) {
        boolean changed = false;

        if (dto.getPhoneNumber() != null && !dto.getPhoneNumber().isBlank()) {
            patient.setPhoneNumberPrimary(dto.getPhoneNumber());
            changed = true;
        }
        if (dto.getEmail() != null && !dto.getEmail().isBlank()) {
            patient.setEmail(dto.getEmail());
            changed = true;
        }
        if (dto.getAddressLine1() != null) { patient.setAddressLine1(dto.getAddressLine1()); changed = true; }
        if (dto.getAddressLine2() != null) { patient.setAddressLine2(dto.getAddressLine2()); changed = true; }
        if (dto.getCity() != null) { patient.setCity(dto.getCity()); changed = true; }
        if (dto.getState() != null) { patient.setState(dto.getState()); changed = true; }
        if (dto.getZipCode() != null) { patient.setZipCode(dto.getZipCode()); changed = true; }
        if (dto.getCountry() != null) { patient.setCountry(dto.getCountry()); changed = true; }

        if (dto.getEmergencyContactName() != null) { patient.setEmergencyContactName(dto.getEmergencyContactName()); changed = true; }
        if (dto.getEmergencyContactPhone() != null) { patient.setEmergencyContactPhone(dto.getEmergencyContactPhone()); changed = true; }
        if (dto.getEmergencyContactRelationship() != null) { patient.setEmergencyContactRelationship(dto.getEmergencyContactRelationship()); changed = true; }

        if (changed) {
            patientRepository.save(patient);
        }
        return changed;
    }
}
