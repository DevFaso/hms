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
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.ConsultationService;
import com.example.hms.service.NotificationService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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

    private final ConsultationRepository consultationRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final EncounterRepository encounterRepository;
    private final RoleValidator roleValidator;
    private final NotificationService notificationService;

    @Override
    public ConsultationResponseDTO createConsultation(ConsultationRequestDTO request, UUID requestingProviderId) {
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + request.getPatientId()));

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + request.getHospitalId()));

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
            consultations = consultationRepository.findByHospitalAndStatuses(
                hospitalId,
                Arrays.asList(ConsultationStatus.REQUESTED, ConsultationStatus.ACKNOWLEDGED, ConsultationStatus.SCHEDULED, ConsultationStatus.IN_PROGRESS)
            );
        }
        return consultations.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> getAllConsultations(ConsultationStatus status) {
        // ── Tenant isolation: non-superadmin scoped to active hospital ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null) {
            return getConsultationsForHospital(activeHospitalId, status);
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
            .orElseThrow(() -> new ResourceNotFoundException("Consultant not found with ID: " + consultantId));

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
                .orElseThrow(() -> new ResourceNotFoundException("Consultant not found with ID: " + updateDTO.getConsultantId()));
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
            .orElseThrow(() -> new ResourceNotFoundException("Consultant not found with ID: " + consultantId));

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
            .orElseThrow(() -> new ResourceNotFoundException("Consultant not found with ID: " + consultantId));

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
            .mapToLong(c -> ChronoUnit.MINUTES.between(c.getRequestedAt(), c.getAssignedAt()))
            .average().orElse(0) / 60.0;

        double avgHoursToComplete = all.stream()
            .filter(c -> c.getCompletedAt() != null && c.getRequestedAt() != null)
            .mapToLong(c -> ChronoUnit.MINUTES.between(c.getRequestedAt(), c.getCompletedAt()))
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
        UUID hospitalId = consultation.getHospital() != null ? consultation.getHospital().getId() : null;
        String patientMrn = null;
        if (consultation.getPatient() != null && hospitalId != null) {
            patientMrn = consultation.getPatient().getMrnForHospital(hospitalId);
        }
        
        return ConsultationResponseDTO.builder()
            .id(consultation.getId())
            .patientId(consultation.getPatient() != null ? consultation.getPatient().getId() : null)
            .patientName(consultation.getPatient() != null ? consultation.getPatient().getFullName() : null)
            .patientMrn(patientMrn)
            .hospitalId(hospitalId)
            .hospitalName(consultation.getHospital() != null ? consultation.getHospital().getName() : null)
            .requestingProviderId(consultation.getRequestingProvider() != null ? consultation.getRequestingProvider().getId() : null)
            .requestingProviderName(consultation.getRequestingProvider() != null ? consultation.getRequestingProvider().getFullName() : null)
            .consultantId(consultation.getConsultant() != null ? consultation.getConsultant().getId() : null)
            .consultantName(consultation.getConsultant() != null ? consultation.getConsultant().getFullName() : null)
            .encounterId(consultation.getEncounter() != null ? consultation.getEncounter().getId() : null)
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
}
