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
import com.example.hms.payload.dto.consultation.ConsultationRequestDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationUpdateDTO;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.ConsultationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
        return toResponseDTO(consultation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> getConsultationsForPatient(UUID patientId) {
        List<Consultation> consultations = consultationRepository.findByPatient_IdOrderByRequestedAtDesc(patientId);
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
        List<Consultation> consultations = consultationRepository.findByRequestingProvider_IdOrderByRequestedAtDesc(providerId);
        return consultations.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsultationResponseDTO> getConsultationsAssignedTo(UUID consultantId, ConsultationStatus status) {
        List<Consultation> consultations;
        if (status != null) {
            consultations = consultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc(consultantId, status);
        } else {
            consultations = consultationRepository.findByConsultant_IdOrderByRequestedAtDesc(consultantId);
        }
        return consultations.stream()
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    public ConsultationResponseDTO acknowledgeConsultation(UUID consultationId, UUID consultantId) {
        Consultation consultation = getConsultationEntity(consultationId);

        if (consultation.getStatus() != ConsultationStatus.REQUESTED) {
            throw new BusinessException("Consultation has already been acknowledged");
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
            if (consultation.getStatus() == ConsultationStatus.ACKNOWLEDGED || consultation.getStatus() == ConsultationStatus.REQUESTED) {
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
    public ConsultationResponseDTO completeConsultation(UUID consultationId, ConsultationUpdateDTO updateDTO) {
        Consultation consultation = getConsultationEntity(consultationId);

        if (consultation.getStatus() == ConsultationStatus.COMPLETED) {
            throw new BusinessException("Consultation is already completed");
        }

        if (consultation.getStatus() == ConsultationStatus.CANCELLED) {
            throw new BusinessException("Cannot complete a cancelled consultation");
        }

        // Apply updates
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

        consultation.setStatus(ConsultationStatus.COMPLETED);
        consultation.setCompletedAt(LocalDateTime.now());

        Consultation saved = consultationRepository.save(consultation);
        log.info("Consultation {} completed", consultationId);

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
            .createdAt(consultation.getCreatedAt())
            .updatedAt(consultation.getUpdatedAt())
            .build();
    }
}
