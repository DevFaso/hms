package com.example.hms.service.impl;

import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralUrgency;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.*;
import com.example.hms.payload.dto.GeneralReferralRequestDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.ReferralAttachmentResponseDTO;
import com.example.hms.repository.*;
import com.example.hms.service.GeneralReferralService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    @Override
    @Transactional
    public GeneralReferralResponseDTO createReferral(GeneralReferralRequestDTO request) {
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

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

        GeneralReferral referral = new GeneralReferral();
        referral.setPatient(patient);
        referral.setHospital(hospital);
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
        return referralRepository.findByPatientIdOrderByCreatedAtDesc(patientId)
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsByReferringProvider(UUID providerId) {
        return referralRepository.findByReferringProviderIdOrderByCreatedAtDesc(providerId)
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsByReceivingProvider(UUID providerId) {
        return referralRepository.findByReceivingProviderIdOrderByCreatedAtDesc(providerId)
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    @Override
    public List<GeneralReferralResponseDTO> getReferralsByHospital(UUID hospitalId, String status) {
        List<GeneralReferral> referrals;
        if (status != null && !status.isBlank()) {
            ReferralStatus referralStatus = ReferralStatus.valueOf(status.toUpperCase());
            referrals = referralRepository.findByHospitalIdAndStatusOrderByCreatedAtDesc(hospitalId, referralStatus);
        } else {
            referrals = referralRepository.findByHospitalIdOrderByCreatedAtDesc(hospitalId);
        }
        return referrals.stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    @Override
    public List<GeneralReferralResponseDTO> getOverdueReferrals() {
        return referralRepository.findOverdueReferrals(LocalDateTime.now())
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    private GeneralReferral findReferral(UUID referralId) {
        return referralRepository.findById(referralId)
            .orElseThrow(() -> new ResourceNotFoundException("Referral not found"));
    }

    private GeneralReferralResponseDTO toResponse(GeneralReferral referral) {
        GeneralReferralResponseDTO dto = new GeneralReferralResponseDTO();
        dto.setId(referral.getId());
        dto.setPatientId(referral.getPatient() != null ? referral.getPatient().getId() : null);
        dto.setPatientName(extractPatientName(referral.getPatient()));
        dto.setHospitalId(referral.getHospital() != null ? referral.getHospital().getId() : null);
        dto.setHospitalName(referral.getHospital() != null ? referral.getHospital().getName() : null);
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
                .collect(Collectors.toList()));
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
