package com.example.hms.service.impl;

import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.ConsultationUrgency;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Consultation;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.consultation.CompleteConsultationRequestDTO;
import com.example.hms.payload.dto.consultation.ConsultationRequestDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationStatsDTO;
import com.example.hms.payload.dto.consultation.ConsultationUpdateDTO;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.ConsultationService;
import com.example.hms.service.NotificationService;
import com.example.hms.utility.ElapsedTime;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ConsultationServiceImpl implements ConsultationService {

    private static final String MSG_CONSULTANT_NOT_FOUND = "Consultant not found with ID: ";
    /** Entity label carried into the {@code safeInit} lazy-load diagnostics. */
    private static final String ENTITY = "Consultation";

    /**
     * Self-reference for proxy-routed internal calls
     * ({@link #getAllConsultations(ConsultationStatus)}
     * → {@link #getConsultationsForHospital(UUID, ConsultationStatus)}).
     * Sonar S6809 — see PatientServiceImpl.setSelf for the full
     * rationale and pattern docstring.
     */
    private ConsultationService self;

    @Autowired
    public void setSelf(@Lazy ConsultationService self) {
        this.self = self;
    }

    private final ConsultationRepository consultationRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientHospitalRegistrationRepository patientHospitalRegistrationRepository;
    private final StaffRepository staffRepository;
    private final EncounterRepository encounterRepository;
    private final RoleValidator roleValidator;
    private final NotificationService notificationService;

    @Override
    public ConsultationResponseDTO createConsultation(ConsultationRequestDTO request, UUID requestingProviderId) {
        Patient patient = patientRepository.findByIdUnscoped(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + request.getPatientId()));

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + request.getHospitalId()));

        if (!patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(patient.getId(), hospital.getId())) {
            throw new BusinessException("Patient is not registered with the specified hospital.");
        }

        Staff requestingProvider = resolveRequestingProvider(requestingProviderId, hospital.getId());

        Encounter encounter = null;
        if (request.getEncounterId() != null) {
            encounter = encounterRepository.findById(request.getEncounterId())
                .orElseThrow(() -> new ResourceNotFoundException("Encounter not found with ID: " + request.getEncounterId()));
        }

        Staff consultant = null;
        if (request.getPreferredConsultantId() != null) {
            consultant = resolveConsultant(request.getPreferredConsultantId(), hospital.getId());
        }

        LocalDateTime slaDueBy = calculateSlaDueBy(request.getUrgency());

        Consultation consultation = Consultation.builder()
            .patient(patient)
            .hospital(hospital)
            .requestingProvider(requestingProvider)
            .consultant(consultant)
            .encounter(encounter)
            .consultationType(request.getConsultationType())
            .specialtyRequested(request.getSpecialtyRequested())
            .reasonForConsult(request.getReasonForConsult())
            .clinicalQuestion(request.getClinicalQuestion())
            .relevantHistory(request.getRelevantHistory())
            .currentMedications(request.getCurrentMedications())
            .urgency(request.getUrgency())
            .status(ConsultationStatus.REQUESTED)
            .requestedAt(LocalDateTime.now())
            .slaDueBy(slaDueBy)
            .isCurbside(request.getIsCurbside() != null ? request.getIsCurbside() : Boolean.FALSE)
            .build();

        Consultation saved = consultationRepository.save(consultation);
        log.info("Created consultation ID {} for patient {} requesting {} specialty", 
            saved.getId(), patient.getId(), request.getSpecialtyRequested());

        return toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ConsultationResponseDTO getConsultation(UUID consultationId) {
        Consultation consultation = getConsultationEntity(consultationId);
        // ── Tenant isolation ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && consultation.getHospital() != null
                && !activeHospitalId.equals(consultation.getHospital().getId())) {
            throw new ResourceNotFoundException("Consultation not found with ID: " + consultationId);
        }
        return toResponseDTO(consultation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> getConsultationsForPatient(UUID patientId) {
        // ── Tenant isolation ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<Consultation> consultations = consultationRepository.findByPatient_IdOrderByRequestedAtDesc(patientId);
        if (activeHospitalId != null) {
            consultations = consultations.stream()
                .filter(c -> c.getHospital() != null && activeHospitalId.equals(c.getHospital().getId()))
                .toList();
        }
        return consultations.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> getConsultationsForHospital(UUID hospitalId, ConsultationStatus status) {
        List<Consultation> consultations;
        if (status != null) {
            consultations = consultationRepository.findByHospital_IdAndStatusOrderByRequestedAtDesc(hospitalId, status);
        } else {
            // No status filter ⇒ return ALL consultations for the hospital, matching
            // the semantics of the cross-tenant SUPER_ADMIN path (which also
            // returns all statuses) and matching the dashboard "Total Consultations"
            // tile (which reads count(*) with no filter). Previously this fell
            // through to findByHospitalAndStatuses with the 4 "active" statuses
            // hard-coded — that silently hid COMPLETED / CANCELLED / DECLINED rows
            // and produced the "Dashboard says 3, list shows 0" UX bug. Pending /
            // active-only worklists already have their own dedicated endpoints
            // (`/consultations/hospital/{id}/pending`).
            consultations = consultationRepository.findByHospital_IdOrderByRequestedAtDesc(hospitalId);
        }
        return consultations.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> getAllConsultations(ConsultationStatus status) {
        // ── Tenant isolation ──
        // Super-admin in global view (no chip / no X-Hospital-Id header)
        // gets the unscoped path so the list matches the dashboard tile,
        // which uses consultationRepository.count() (no scope filter).
        // Without this carve-out, a super-admin whose JWT carries a
        // primary-hospital fallback would see a scoped list (often 0)
        // while the dashboard count shows the system-wide total —
        // exactly the symptom the develop-deployment review surfaced
        // (count tile 3, list page "No consultations across any of your
        // hospitals"). Mirrors the SuperAdminDashboardServiceImpl
        // getRecentPrescriptions fix and the
        // ConsultationServiceImpl#getRecentForSuperAdmin pattern.
        //
        // We use ONLY the JWT-claim signal (ctx.isSuperAdmin()), NOT
        // RoleValidator.isSuperAdminFromAuth(). Authority-based detection
        // can be true in impersonation / authority-inflation flows where
        // the principal holds the ROLE_SUPER_ADMIN authority but the JWT
        // claim isn't set; trusting it for cross-tenant access would
        // widen the blast radius of a stolen / inflated authority list.
        // The JWT claim is the load-bearing signal RoleValidator's own
        // step 1 trusts (Copilot review on PR fix branch).
        HospitalContext ctx = HospitalContextHolder.getContextOrEmpty();
        boolean superAdminGlobal = ctx.isSuperAdmin() && !ctx.isHeaderOverridden();
        UUID activeHospitalId = superAdminGlobal
            ? null
            : roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null) {
            // Sonar S6809: route through the proxy so the inner
            // method's @Transactional(readOnly=true) is honored.
            return self.getConsultationsForHospital(activeHospitalId, status);
        }
        List<Consultation> consultations;
        if (status != null) {
            consultations = consultationRepository.findByStatusOrderByRequestedAtDesc(status);
        } else {
            consultations = consultationRepository.findAllByOrderByRequestedAtDesc();
        }
        return consultations.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> getRecentForSuperAdmin(Pageable pageable) {
        return consultationRepository.findAll(pageable)
            .map(this::toResponseDTO)
            .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> getConsultationsRequestedBy(UUID providerId) {
        // ── Tenant isolation ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<Consultation> consultations = consultationRepository.findByRequestingProvider_IdOrderByRequestedAtDesc(providerId);
        if (activeHospitalId != null) {
            consultations = consultations.stream()
                .filter(c -> c.getHospital() != null && activeHospitalId.equals(c.getHospital().getId()))
                .toList();
        }
        return consultations.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> getConsultationsAssignedTo(UUID consultantId, ConsultationStatus status) {
        // ── Tenant isolation ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<Consultation> consultations;
        if (status != null) {
            consultations = consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(consultantId, status);
        } else {
            consultations = consultationRepository.findByConsultant_IdOrderByRequestedAtDesc(consultantId);
        }
        if (activeHospitalId != null) {
            consultations = consultations.stream()
                .filter(c -> c.getHospital() != null && activeHospitalId.equals(c.getHospital().getId()))
                .toList();
        }
        return consultations.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    public ConsultationResponseDTO acknowledgeConsultation(UUID consultationId, UUID consultantId) {
        Consultation consultation = getConsultationEntity(consultationId);

        if (consultation.getStatus() != ConsultationStatus.ASSIGNED &&
            consultation.getStatus() != ConsultationStatus.REQUESTED) {
            throw new BusinessException("Consultation must be in ASSIGNED or REQUESTED status to acknowledge (current: " + consultation.getStatus() + ")");
        }

        Staff consultant = staffRepository.findById(consultantId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_CONSULTANT_NOT_FOUND + consultantId));

        consultation.setConsultant(consultant);
        consultation.setStatus(ConsultationStatus.ACKNOWLEDGED);
        consultation.setAcknowledgedAt(LocalDateTime.now());

        Consultation saved = consultationRepository.save(consultation);
        log.info("Consultation {} acknowledged by consultant {}", consultationId, consultantId);

        return toResponseDTO(saved);
    }

    @Override
    public ConsultationResponseDTO updateConsultation(UUID consultationId, ConsultationUpdateDTO updateDTO) {
        Consultation consultation = getConsultationEntity(consultationId);

        if (updateDTO.getConsultantId() != null && !updateDTO.getConsultantId().equals(consultation.getConsultant() != null ? consultation.getConsultant().getId() : null)) {
            Staff consultant = staffRepository.findById(updateDTO.getConsultantId())
                .orElseThrow(() -> new ResourceNotFoundException(MSG_CONSULTANT_NOT_FOUND + updateDTO.getConsultantId()));
            consultation.setConsultant(consultant);
        }

        if (updateDTO.getScheduledAt() != null) {
            consultation.setScheduledAt(updateDTO.getScheduledAt());
            if (consultation.getStatus() == ConsultationStatus.ACKNOWLEDGED ||
                consultation.getStatus() == ConsultationStatus.ASSIGNED ||
                consultation.getStatus() == ConsultationStatus.REQUESTED) {
                consultation.setStatus(ConsultationStatus.SCHEDULED);
            }
        }

        if (updateDTO.getConsultantNote() != null) {
            consultation.setConsultantNote(updateDTO.getConsultantNote());
        }

        if (updateDTO.getRecommendations() != null) {
            consultation.setRecommendations(updateDTO.getRecommendations());
        }

        if (updateDTO.getFollowUpRequired() != null) {
            consultation.setFollowUpRequired(updateDTO.getFollowUpRequired());
        }

        if (updateDTO.getFollowUpInstructions() != null) {
            consultation.setFollowUpInstructions(updateDTO.getFollowUpInstructions());
        }

        Consultation saved = consultationRepository.save(consultation);
        return toResponseDTO(saved);
    }

    @Override
    public ConsultationResponseDTO completeConsultation(UUID consultationId, CompleteConsultationRequestDTO request) {
        Consultation consultation = getConsultationEntity(consultationId);

        if (consultation.getStatus() == ConsultationStatus.COMPLETED) {
            throw new BusinessException("Consultation is already completed");
        }
        if (consultation.getStatus() == ConsultationStatus.CANCELLED) {
            throw new BusinessException("Cannot complete a cancelled consultation");
        }

        consultation.setRecommendations(request.getRecommendations());
        if (request.getConsultantNote() != null) {
            consultation.setConsultantNote(request.getConsultantNote());
        }
        if (request.getFollowUpRequired() != null) {
            consultation.setFollowUpRequired(request.getFollowUpRequired());
        }
        if (request.getFollowUpInstructions() != null) {
            consultation.setFollowUpInstructions(request.getFollowUpInstructions());
        }

        consultation.setStatus(ConsultationStatus.COMPLETED);
        consultation.setCompletedAt(LocalDateTime.now());

        Consultation saved = consultationRepository.save(consultation);
        log.info("Consultation {} completed", consultationId);

        // Notify the requesting provider
        try {
            if (consultation.getRequestingProvider() != null) {
                String requesterUsername = consultation.getRequestingProvider().getUser().getUsername();
                String consultantName = consultation.getConsultant() != null ? consultation.getConsultant().getFullName() : "The consultant";
                String patientName = consultation.getPatient() != null ? consultation.getPatient().getFullName() : "your patient";
                notificationService.createNotification(
                    "Consultation completed for " + patientName +
                    " (" + consultation.getSpecialtyRequested() + ") by " + consultantName +
                    ". Recommendations are now available.",
                    requesterUsername);
            }
        } catch (Exception e) {
            log.warn("Failed to send completion notification for consultation {}: {}", consultationId, e.getMessage());
        }

        return toResponseDTO(saved);
    }

    @Override
    public ConsultationResponseDTO cancelConsultation(UUID consultationId, String cancellationReason) {
        Consultation consultation = getConsultationEntity(consultationId);

        if (consultation.getStatus() == ConsultationStatus.COMPLETED) {
            throw new BusinessException("Cannot cancel a completed consultation");
        }

        if (consultation.getStatus() == ConsultationStatus.CANCELLED) {
            throw new BusinessException("Consultation is already cancelled");
        }

        consultation.setStatus(ConsultationStatus.CANCELLED);
        consultation.setCancelledAt(LocalDateTime.now());
        consultation.setCancellationReason(cancellationReason);

        Consultation saved = consultationRepository.save(consultation);
        log.info("Consultation {} cancelled: {}", consultationId, cancellationReason);

        return toResponseDTO(saved);
    }

    @Override
    public ConsultationResponseDTO scheduleConsultation(UUID consultationId, LocalDateTime scheduledAt, String scheduleNote) {
        Consultation consultation = getConsultationEntity(consultationId);

        if (consultation.getStatus() == ConsultationStatus.COMPLETED ||
            consultation.getStatus() == ConsultationStatus.CANCELLED ||
            consultation.getStatus() == ConsultationStatus.DECLINED) {
            throw new BusinessException("Cannot schedule a " + consultation.getStatus() + " consultation");
        }

        consultation.setScheduledAt(scheduledAt);
        consultation.setStatus(ConsultationStatus.SCHEDULED);
        if (scheduleNote != null && !scheduleNote.isBlank()) {
            consultation.setConsultantNote(scheduleNote);
        }

        Consultation saved = consultationRepository.save(consultation);
        log.info("Consultation {} scheduled for {}", consultationId, scheduledAt);

        return toResponseDTO(saved);
    }

    @Override
    public ConsultationResponseDTO startConsultation(UUID consultationId) {
        Consultation consultation = getConsultationEntity(consultationId);

        if (consultation.getStatus() != ConsultationStatus.SCHEDULED &&
            consultation.getStatus() != ConsultationStatus.ACKNOWLEDGED &&
            consultation.getStatus() != ConsultationStatus.ASSIGNED) {
            throw new BusinessException("Consultation must be ASSIGNED, ACKNOWLEDGED or SCHEDULED to start (current: " + consultation.getStatus() + ")");
        }

        consultation.setStatus(ConsultationStatus.IN_PROGRESS);
        consultation.setStartedAt(LocalDateTime.now());

        Consultation saved = consultationRepository.save(consultation);
        log.info("Consultation {} started", consultationId);

        return toResponseDTO(saved);
    }

    @Override
    public ConsultationResponseDTO declineConsultation(UUID consultationId, String declineReason) {
        Consultation consultation = getConsultationEntity(consultationId);

        if (consultation.getStatus() == ConsultationStatus.COMPLETED) {
            throw new BusinessException("Cannot decline a completed consultation");
        }
        if (consultation.getStatus() == ConsultationStatus.CANCELLED) {
            throw new BusinessException("Cannot decline a cancelled consultation");
        }
        if (consultation.getStatus() == ConsultationStatus.DECLINED) {
            throw new BusinessException("Consultation is already declined");
        }

        consultation.setStatus(ConsultationStatus.DECLINED);
        consultation.setDeclinedAt(LocalDateTime.now());
        consultation.setDeclineReason(declineReason);

        Consultation saved = consultationRepository.save(consultation);
        log.info("Consultation {} declined: {}", consultationId, declineReason);

        return toResponseDTO(saved);
    }

    @Override
    public ConsultationResponseDTO assignConsultation(UUID consultationId, UUID consultantId, UUID assignedById, String assignmentNote) {
        Consultation consultation = getConsultationEntity(consultationId);

        if (consultation.getStatus() != ConsultationStatus.REQUESTED) {
            throw new BusinessException("Only REQUESTED consultations can be assigned (current status: " + consultation.getStatus() + ")");
        }

        Staff consultant = staffRepository.findById(consultantId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_CONSULTANT_NOT_FOUND + consultantId));

        consultation.setConsultant(consultant);
        consultation.setStatus(ConsultationStatus.ASSIGNED);
        consultation.setAssignedAt(LocalDateTime.now());
        consultation.setAssignedById(assignedById);
        if (assignmentNote != null && !assignmentNote.isBlank()) {
            consultation.setConsultantNote(assignmentNote);
        }

        Consultation saved = consultationRepository.save(consultation);
        log.info("Consultation {} assigned to consultant {} by {}", consultationId, consultantId, assignedById);

        // Notify the assigned consultant
        try {
            String consultantUsername = consultant.getUser().getUsername();
            String patientName = consultation.getPatient() != null ? consultation.getPatient().getFullName() : "a patient";
            notificationService.createNotification(
                "You have been assigned a consultation request for " + patientName +
                " — Specialty: " + consultation.getSpecialtyRequested() +
                " (Urgency: " + consultation.getUrgency() + ")",
                consultantUsername);
        } catch (Exception e) {
            log.warn("Failed to send assignment notification for consultation {}: {}", consultationId, e.getMessage());
        }

        return toResponseDTO(saved);
    }

    @Override
    public ConsultationResponseDTO reassignConsultation(UUID consultationId, UUID consultantId, UUID assignedById, String reassignmentReason) {
        Consultation consultation = getConsultationEntity(consultationId);

        if (consultation.getStatus() == ConsultationStatus.COMPLETED) {
            throw new BusinessException("Cannot reassign a completed consultation");
        }
        if (consultation.getStatus() == ConsultationStatus.CANCELLED) {
            throw new BusinessException("Cannot reassign a cancelled consultation");
        }

        Staff consultant = staffRepository.findById(consultantId)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_CONSULTANT_NOT_FOUND + consultantId));

        UUID previousConsultantId = consultation.getConsultant() != null ? consultation.getConsultant().getId() : null;
        consultation.setConsultant(consultant);
        consultation.setStatus(ConsultationStatus.ASSIGNED);
        consultation.setAssignedAt(LocalDateTime.now());
        consultation.setAssignedById(assignedById);

        Consultation saved = consultationRepository.save(consultation);
        log.info("Consultation {} reassigned from {} to {} — reason: {}", consultationId, previousConsultantId, consultantId, reassignmentReason);
        return toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> getPendingConsultations(UUID hospitalId) {
        List<Consultation> consultations = consultationRepository.findByHospitalAndStatuses(
            hospitalId,
            Arrays.asList(ConsultationStatus.REQUESTED, ConsultationStatus.ACKNOWLEDGED)
        );
        return consultations.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> getMyConsultations(UUID consultantStaffId) {
        List<Consultation> consultations = consultationRepository.findByConsultant_IdOrderByRequestedAtDesc(consultantStaffId);
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null) {
            consultations = consultations.stream()
                .filter(c -> c.getHospital() != null && activeHospitalId.equals(c.getHospital().getId()))
                .toList();
        }
        return consultations.stream().map(this::toResponseDTO).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> getOverdueConsultations(UUID hospitalId) {
        List<ConsultationStatus> terminalStatuses = Arrays.asList(
            ConsultationStatus.COMPLETED, ConsultationStatus.CANCELLED, ConsultationStatus.DECLINED);
        List<Consultation> overdue = consultationRepository.findOverdueConsultations(LocalDateTime.now(), terminalStatuses);
        if (hospitalId != null) {
            overdue = overdue.stream()
                .filter(c -> c.getHospital() != null && hospitalId.equals(c.getHospital().getId()))
                .toList();
        }
        return overdue.stream().map(this::toResponseDTO).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ConsultationStatsDTO getStats(UUID hospitalId) {
        List<Consultation> all = hospitalId != null
            ? consultationRepository.findAllByOrderByRequestedAtDesc().stream()
                .filter(c -> c.getHospital() != null && hospitalId.equals(c.getHospital().getId()))
                .toList()
            : consultationRepository.findAllByOrderByRequestedAtDesc();

        List<ConsultationStatus> terminalStatuses = Arrays.asList(
            ConsultationStatus.COMPLETED, ConsultationStatus.CANCELLED, ConsultationStatus.DECLINED);
        LocalDateTime now = LocalDateTime.now();

        long total = all.size();
        long requested = all.stream().filter(c -> c.getStatus() == ConsultationStatus.REQUESTED).count();
        long active = all.stream().filter(c -> List.of(ConsultationStatus.ASSIGNED,
            ConsultationStatus.ACKNOWLEDGED, ConsultationStatus.SCHEDULED,
            ConsultationStatus.IN_PROGRESS).contains(c.getStatus())).count();
        long completed = all.stream().filter(c -> c.getStatus() == ConsultationStatus.COMPLETED).count();
        long cancelled = all.stream().filter(c -> c.getStatus() == ConsultationStatus.CANCELLED).count();
        long declined = all.stream().filter(c -> c.getStatus() == ConsultationStatus.DECLINED).count();
        long overdue = all.stream().filter(c ->
            c.getSlaDueBy() != null && c.getSlaDueBy().isBefore(now)
            && !terminalStatuses.contains(c.getStatus())).count();

        double avgHoursToAssign = all.stream()
            .filter(c -> c.getAssignedAt() != null && c.getRequestedAt() != null)
            .mapToLong(c -> ElapsedTime.minutesBetween(c.getRequestedAt(), c.getAssignedAt()))
            .average().orElse(0) / 60.0;

        double avgHoursToComplete = all.stream()
            .filter(c -> c.getCompletedAt() != null && c.getRequestedAt() != null)
            .mapToLong(c -> ElapsedTime.minutesBetween(c.getRequestedAt(), c.getCompletedAt()))
            .average().orElse(0) / 60.0;

        Map<String, Long> bySpecialty = all.stream()
            .filter(c -> c.getSpecialtyRequested() != null)
            .collect(Collectors.groupingBy(Consultation::getSpecialtyRequested, Collectors.counting()))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                (a, b) -> a, LinkedHashMap::new));

        return ConsultationStatsDTO.builder()
            .total(total).requested(requested).active(active)
            .completed(completed).cancelled(cancelled).declined(declined).overdue(overdue)
            .avgHoursToAssign(Math.round(avgHoursToAssign * 10.0) / 10.0)
            .avgHoursToComplete(Math.round(avgHoursToComplete * 10.0) / 10.0)
            .bySpecialty(bySpecialty)
            .build();
    }

    // Helper methods

    private Consultation getConsultationEntity(UUID consultationId) {
        return consultationRepository.findById(consultationId)
            .orElseThrow(() -> new ResourceNotFoundException("Consultation not found with ID: " + consultationId));
    }

    private LocalDateTime calculateSlaDueBy(ConsultationUrgency urgency) {
        LocalDateTime now = LocalDateTime.now();
        return switch (urgency) {
            case STAT, EMERGENCY -> now.plusHours(2);
            case URGENT -> now.plusHours(24);
            case ROUTINE -> now.plusDays(7);
        };
    }

    private Staff resolveRequestingProvider(UUID identifier, UUID hospitalId) {
        return resolveStaff(identifier, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Requesting provider not found with ID: " + identifier));
    }

    private Staff resolveConsultant(UUID identifier, UUID hospitalId) {
        return resolveStaff(identifier, hospitalId).orElse(null);
    }

    private Optional<Staff> resolveStaff(UUID identifier, UUID hospitalId) {
        Optional<Staff> byStaffId = staffRepository.findById(identifier);
        if (byStaffId.isPresent()) {
            return byStaffId;
        }

        Optional<Staff> byUserAndHospital = staffRepository.findByUserIdAndHospitalId(identifier, hospitalId);
        if (byUserAndHospital.isPresent()) {
            return byUserAndHospital;
        }

        return staffRepository.findFirstByUserIdOrderByCreatedAtAsc(identifier);
    }

    private ConsultationResponseDTO toResponseDTO(Consultation consultation) {
        // Defensive lazy reads. Each association is initialised via `safeInit`
        // so that a dangling FK (parent row hard-deleted while the consultation
        // still references it) returns null instead of letting Hibernate throw
        // EntityNotFoundException — which would 500 the entire list response
        // via GlobalExceptionHandler.handleEntityNotFound.
        UUID consultationId = consultation.getId();
        Patient patient   = safeInit(consultation.getPatient(),            ENTITY, consultationId, "patient");
        Hospital hospital = safeInit(consultation.getHospital(),           ENTITY, consultationId, "hospital");
        Staff requester   = safeInit(consultation.getRequestingProvider(), ENTITY, consultationId, "requestingProvider");
        Staff consultant  = safeInit(consultation.getConsultant(),         ENTITY, consultationId, "consultant");
        Encounter encounter = safeInit(consultation.getEncounter(),        ENTITY, consultationId, "encounter");

        UUID hospitalId = hospital != null ? hospital.getId() : null;
        String patientMrn = null;
        if (patient != null && hospitalId != null) {
            try {
                patientMrn = patient.getMrnForHospital(hospitalId);
            } catch (EntityNotFoundException | JpaObjectRetrievalFailureException e) {
                // hospitalRegistrations is LAZY — a dangling registration FK
                // would land here. MRN simply degrades to null.
                log.warn("⚠️ Consultation({}) patient {} has dangling hospitalRegistration FK; MRN unavailable.",
                    consultationId, patient.getId());
            }
        }

        return ConsultationResponseDTO.builder()
            .id(consultationId)
            .patientId(patient != null ? patient.getId() : null)
            .patientName(patient != null ? patient.getFullName() : null)
            .patientMrn(patientMrn)
            .hospitalId(hospitalId)
            .hospitalName(hospital != null ? hospital.getName() : null)
            .requestingProviderId(requester != null ? requester.getId() : null)
            .requestingProviderName(requester != null ? requester.getFullName() : null)
            .consultantId(consultant != null ? consultant.getId() : null)
            .consultantName(consultant != null ? consultant.getFullName() : null)
            .encounterId(encounter != null ? encounter.getId() : null)
            .consultationType(consultation.getConsultationType())
            .specialtyRequested(consultation.getSpecialtyRequested())
            .reasonForConsult(consultation.getReasonForConsult())
            .clinicalQuestion(consultation.getClinicalQuestion())
            .relevantHistory(consultation.getRelevantHistory())
            .currentMedications(consultation.getCurrentMedications())
            .urgency(consultation.getUrgency())
            .status(consultation.getStatus())
            .requestedAt(consultation.getRequestedAt())
            .acknowledgedAt(consultation.getAcknowledgedAt())
            .scheduledAt(consultation.getScheduledAt())
            .completedAt(consultation.getCompletedAt())
            .cancelledAt(consultation.getCancelledAt())
            .cancellationReason(consultation.getCancellationReason())
            .consultantNote(consultation.getConsultantNote())
            .recommendations(consultation.getRecommendations())
            .followUpRequired(consultation.getFollowUpRequired())
            .followUpInstructions(consultation.getFollowUpInstructions())
            .slaDueBy(consultation.getSlaDueBy())
            .isCurbside(consultation.getIsCurbside())
            .assignedAt(consultation.getAssignedAt())
            .assignedById(consultation.getAssignedById())
            .startedAt(consultation.getStartedAt())
            .declinedAt(consultation.getDeclinedAt())
            .declineReason(consultation.getDeclineReason())
            .createdAt(consultation.getCreatedAt())
            .updatedAt(consultation.getUpdatedAt())
            .build();
    }

    /**
     * Safely initialise a Hibernate lazy proxy. Returns {@code null} when the
     * referenced row was hard-deleted while this row still references it
     * (dangling FK) instead of letting {@link EntityNotFoundException} /
     * {@link JpaObjectRetrievalFailureException} propagate out — which would
     * otherwise be mapped to HTTP 500 by
     * {@code GlobalExceptionHandler.handleEntityNotFound} and break the whole
     * list response over a single bad row.
     */
    private static <T> T safeInit(T proxyOrEntity, String parentEntity, Object parentId, String association) {
        if (proxyOrEntity == null) return null;
        try {
            Hibernate.initialize(proxyOrEntity);
            return proxyOrEntity;
        } catch (EntityNotFoundException | JpaObjectRetrievalFailureException e) {
            log.warn("⚠️ {}({}) has a dangling FK on '{}' — referenced row was deleted. " +
                     "Returning null for this association; DB cleanup required.",
                     parentEntity, parentId, association);
            return null;
        }
    }
}
