package com.example.hms.service.impl;

import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralUrgency;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Department;
import com.example.hms.model.GeneralReferral;
import com.example.hms.model.GeneralReferralAttachment;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.GeneralReferralRequestDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.ReferralAttachmentResponseDTO;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.GeneralReferralRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.GeneralReferralService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service implementation for multi-specialty general referrals
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GeneralReferralServiceImpl implements GeneralReferralService {

    private final GeneralReferralRepository referralRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final DepartmentRepository departmentRepository;
    private final RoleValidator roleValidator;

    @Override
    @Transactional
    public GeneralReferralResponseDTO createReferral(GeneralReferralRequestDTO request) {
        Patient patient = patientRepository.findByIdUnscoped(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", request.getPatientId()));

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found"));

        Staff referringProvider = staffRepository.findById(request.getReferringProviderId())
            .orElseThrow(() -> new ResourceNotFoundException("Referring provider not found"));

        Staff receivingProvider = null;
        if (request.getReceivingProviderId() != null) {
            receivingProvider = staffRepository.findById(request.getReceivingProviderId())
                .orElseThrow(() -> new ResourceNotFoundException("Receiving provider not found"));
        }

        Department targetDepartment = null;
        if (request.getTargetDepartmentId() != null) {
            targetDepartment = departmentRepository.findById(request.getTargetDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Target department not found"));
        }

        Hospital receivingHospital = null;
        if (request.getReceivingHospitalId() != null) {
            receivingHospital = hospitalRepository.findById(request.getReceivingHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("Receiving hospital not found"));
        }

        Department sourceDepartment = null;
        if (request.getSourceDepartmentId() != null) {
            sourceDepartment = departmentRepository.findById(request.getSourceDepartmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Source department not found"));
        }

        GeneralReferral referral = new GeneralReferral();
        referral.setPatient(patient);
        referral.setHospital(hospital);
        referral.setReceivingHospital(receivingHospital);
        referral.setSourceDepartment(sourceDepartment);
        referral.setReferringProvider(referringProvider);
        referral.setReceivingProvider(receivingProvider);
        referral.setTargetSpecialty(request.getTargetSpecialty());
        referral.setTargetDepartment(targetDepartment);
        referral.setTargetFacilityName(request.getTargetFacilityName());
        referral.setReferralType(request.getReferralType());
        referral.setStatus(ReferralStatus.DRAFT);
        referral.setUrgency(request.getUrgency());
        referral.setReferralReason(request.getReferralReason());
        referral.setClinicalIndication(request.getClinicalIndication());
        referral.setClinicalSummary(request.getClinicalSummary());
        referral.setCurrentMedications(copyList(request.getCurrentMedications()));
        referral.setDiagnoses(copyList(request.getDiagnoses()));
        referral.setClinicalQuestion(request.getClinicalQuestion());
        referral.setAnticipatedTreatment(request.getAnticipatedTreatment());
        referral.setInsuranceAuthNumber(request.getInsuranceAuthNumber());
        referral.setMetadata(request.getMetadata());
        referral.setPriorityScore(calculatePriorityScore(request.getUrgency()));

        referral = referralRepository.save(referral);
        return toResponse(referral);
    }

    @Override
    public GeneralReferralResponseDTO getReferral(UUID referralId) {
        return toResponse(findReferral(referralId));
    }

    @Override
    @Transactional
    public GeneralReferralResponseDTO submitReferral(UUID referralId) {
        GeneralReferral referral = findReferral(referralId);
        referral.submit();
        referral = referralRepository.save(referral);
        return toResponse(referral);
    }

    @Override
    @Transactional
    public GeneralReferralResponseDTO acknowledgeReferral(UUID referralId, String notes, UUID receivingProviderId) {
        GeneralReferral referral = findReferral(referralId);
        Staff receivingProvider = staffRepository.findById(receivingProviderId)
            .orElseThrow(() -> new ResourceNotFoundException("Receiving provider not found"));
        referral.acknowledge(notes, receivingProvider);
        referral = referralRepository.save(referral);
        return toResponse(referral);
    }

    @Override
    @Transactional
    public GeneralReferralResponseDTO completeReferral(UUID referralId, String summary, String followUp) {
        GeneralReferral referral = findReferral(referralId);
        referral.complete(summary, followUp);
        referral = referralRepository.save(referral);
        return toResponse(referral);
    }

    @Override
    @Transactional
    public void cancelReferral(UUID referralId, String reason) {
        GeneralReferral referral = findReferral(referralId);
        referral.cancel(reason);
        referralRepository.save(referral);
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsByPatient(UUID patientId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<GeneralReferral> referrals;
        if (activeHospitalId != null) {
            referrals = referralRepository.findByPatientIdAndHospitalIdOrderByCreatedAtDesc(patientId, activeHospitalId);
        } else {
            referrals = referralRepository.findByPatientIdOrderByCreatedAtDesc(patientId);
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsByReferringProvider(UUID providerId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<GeneralReferral> referrals;
        if (activeHospitalId != null) {
            referrals = referralRepository.findByReferringProviderIdAndHospitalIdOrderByCreatedAtDesc(providerId, activeHospitalId);
        } else {
            referrals = referralRepository.findByReferringProviderIdOrderByCreatedAtDesc(providerId);
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsByReceivingProvider(UUID providerId) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<GeneralReferral> referrals;
        if (activeHospitalId != null) {
            referrals = referralRepository.findByReceivingProviderIdAndHospitalIdOrderByCreatedAtDesc(providerId, activeHospitalId);
        } else {
            referrals = referralRepository.findByReceivingProviderIdOrderByCreatedAtDesc(providerId);
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsByHospital(UUID hospitalId, String status) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        UUID effectiveHospitalId = activeHospitalId != null ? activeHospitalId : hospitalId;
        List<GeneralReferral> referrals;
        if (status != null && !status.isBlank()) {
            ReferralStatus referralStatus = ReferralStatus.valueOf(status.toUpperCase());
            referrals = referralRepository.findByHospitalIdAndStatusOrderByCreatedAtDesc(effectiveHospitalId, referralStatus);
        } else {
            referrals = referralRepository.findByHospitalIdOrderByCreatedAtDesc(effectiveHospitalId);
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getAllReferrals(String status) {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null) {
            return getReferralsByHospital(activeHospitalId, status);
        }
        List<GeneralReferral> referrals;
        if (status != null && !status.isBlank()) {
            ReferralStatus referralStatus = ReferralStatus.valueOf(status.toUpperCase());
            referrals = referralRepository.findByStatusOrderByCreatedAtDesc(referralStatus);
        } else {
            referrals = referralRepository.findAllByOrderByCreatedAtDesc();
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    @Override
    public List<GeneralReferralResponseDTO> getOverdueReferrals() {
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        List<GeneralReferral> referrals;
        if (activeHospitalId != null) {
            referrals = referralRepository.findOverdueReferralsByHospital(activeHospitalId, LocalDateTime.now());
        } else {
            referrals = referralRepository.findOverdueReferrals(LocalDateTime.now());
        }
        return referrals.stream()
            .map(this::toResponse)
            .toList();
    }

    private GeneralReferral findReferral(UUID referralId) {
        GeneralReferral referral = referralRepository.findById(referralId)
            .orElseThrow(() -> new ResourceNotFoundException("Referral not found"));
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && referral.getHospital() != null
                && !activeHospitalId.equals(referral.getHospital().getId())) {
            throw new ResourceNotFoundException("Referral not found");
        }
        return referral;
    }

    private GeneralReferralResponseDTO toResponse(GeneralReferral referral) {
        GeneralReferralResponseDTO dto = new GeneralReferralResponseDTO();
        dto.setId(referral.getId());
        dto.setPatientId(referral.getPatient() != null ? referral.getPatient().getId() : null);
        dto.setPatientName(extractPatientName(referral.getPatient()));
        dto.setHospitalId(referral.getHospital() != null ? referral.getHospital().getId() : null);
        dto.setHospitalName(referral.getHospital() != null ? referral.getHospital().getName() : null);
        dto.setReceivingHospitalId(referral.getReceivingHospital() != null ? referral.getReceivingHospital().getId() : null);
        dto.setReceivingHospitalName(referral.getReceivingHospital() != null ? referral.getReceivingHospital().getName() : null);
        dto.setSourceDepartmentId(referral.getSourceDepartment() != null ? referral.getSourceDepartment().getId() : null);
        dto.setSourceDepartmentName(referral.getSourceDepartment() != null ? referral.getSourceDepartment().getName() : null);
        dto.setReferringProviderId(referral.getReferringProvider() != null ? referral.getReferringProvider().getId() : null);
        dto.setReferringProviderName(extractStaffName(referral.getReferringProvider()));
        dto.setReceivingProviderId(referral.getReceivingProvider() != null ? referral.getReceivingProvider().getId() : null);
        dto.setReceivingProviderName(extractStaffName(referral.getReceivingProvider()));
        dto.setTargetSpecialty(referral.getTargetSpecialty());
        dto.setTargetDepartmentId(referral.getTargetDepartment() != null ? referral.getTargetDepartment().getId() : null);
        dto.setTargetDepartmentName(referral.getTargetDepartment() != null ? referral.getTargetDepartment().getName() : null);
        dto.setTargetFacilityName(referral.getTargetFacilityName());
        dto.setReferralType(referral.getReferralType());
        dto.setStatus(referral.getStatus());
        dto.setUrgency(referral.getUrgency());
        dto.setReferralReason(referral.getReferralReason());
        dto.setClinicalIndication(referral.getClinicalIndication());
        dto.setClinicalSummary(referral.getClinicalSummary());
        dto.setCurrentMedications(referral.getCurrentMedications());
        dto.setDiagnoses(referral.getDiagnoses());
        dto.setClinicalQuestion(referral.getClinicalQuestion());
        dto.setAnticipatedTreatment(referral.getAnticipatedTreatment());
        dto.setSubmittedAt(referral.getSubmittedAt());
        dto.setSlaDueAt(referral.getSlaDueAt());
        dto.setAcknowledgedAt(referral.getAcknowledgedAt());
        dto.setAcknowledgementNotes(referral.getAcknowledgementNotes());
        dto.setScheduledAppointmentAt(referral.getScheduledAppointmentAt());
        dto.setAppointmentLocation(referral.getAppointmentLocation());
        dto.setCompletedAt(referral.getCompletedAt());
        dto.setCompletionSummary(referral.getCompletionSummary());
        dto.setFollowUpRecommendations(referral.getFollowUpRecommendations());
        dto.setCancellationReason(referral.getCancellationReason());
        dto.setInsuranceAuthNumber(referral.getInsuranceAuthNumber());
        dto.setPriorityScore(referral.getPriorityScore());
        dto.setMetadata(referral.getMetadata());
        dto.setAttachments(referral.getAttachments() == null ? List.of() :
            referral.getAttachments().stream()
                .map(this::toAttachmentResponse)
                .toList());
        dto.setIsOverdue(referral.isOverdue());
        dto.setCreatedAt(referral.getCreatedAt());
        dto.setUpdatedAt(referral.getUpdatedAt());
        return dto;
    }

    private ReferralAttachmentResponseDTO toAttachmentResponse(GeneralReferralAttachment attachment) {
        ReferralAttachmentResponseDTO dto = new ReferralAttachmentResponseDTO();
        dto.setId(attachment.getId());
        dto.setReferralId(attachment.getReferral() != null ? attachment.getReferral().getId() : null);
        dto.setStorageKey(attachment.getStorageKey());
        dto.setDisplayName(attachment.getDisplayName());
        dto.setCategory(attachment.getCategory());
        dto.setContentType(attachment.getContentType());
        dto.setSizeBytes(attachment.getSizeBytes());
        dto.setUploadedById(attachment.getUploadedBy() != null ? attachment.getUploadedBy().getId() : null);
        dto.setUploadedByName(extractStaffName(attachment.getUploadedBy()));
        dto.setUploadedAt(attachment.getUploadedAt());
        dto.setDescription(attachment.getDescription());
        return dto;
    }

    private List<Map<String, String>> copyList(List<Map<String, String>> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(source);
    }

    private int calculatePriorityScore(ReferralUrgency urgency) {
        if (urgency == null) {
            return 0;
        }
        return switch (urgency) {
            case EMERGENCY -> 100;
            case URGENT -> 75;
            case PRIORITY -> 50;
            case ROUTINE -> 25;
        };
    }

    private String extractStaffName(Staff staff) {
        if (staff == null) {
            return null;
        }
        if (staff.getName() != null && !staff.getName().isBlank()) {
            return staff.getName();
        }
        return staff.getFullName();
    }

    private String extractPatientName(Patient patient) {
        if (patient == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        if (patient.getFirstName() != null) {
            builder.append(patient.getFirstName().trim());
        }
        if (patient.getLastName() != null) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(patient.getLastName().trim());
        }
        return builder.isEmpty() ? null : builder.toString();
    }
}
