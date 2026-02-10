package com.example.hms.service.impl;

import com.example.hms.enums.ObgynReferralStatus;
import com.example.hms.enums.ObgynReferralUrgency;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ObgynReferralMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.referral.ObgynReferral;
import com.example.hms.model.referral.ObgynReferralMessage;
import com.example.hms.model.referral.ReferralAttachment;
import com.example.hms.payload.dto.referral.ObgynReferralAcknowledgeRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralCancelRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralCompletionRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralCreateRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralMessageDTO;
import com.example.hms.payload.dto.referral.ObgynReferralMessageRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralResponseDTO;
import com.example.hms.payload.dto.referral.ReferralAttachmentUploadDTO;
import com.example.hms.payload.dto.referral.ReferralStatusSummaryDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ObgynReferralMessageRepository;
import com.example.hms.repository.ObgynReferralRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.ObgynReferralService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ObgynReferralServiceImpl implements ObgynReferralService {

    private static final String PATIENT_NOT_FOUND_WITH_ID = "patient.notFoundWithId";
    private static final String HOSPITAL_NOT_FOUND_WITH_ID = "hospital.notFoundWithId";
    private static final String USER_NOT_FOUND_WITH_USERNAME = "user.notFoundByUsername";
    private static final String USER_NOT_FOUND_WITH_ID = "user.notFoundWithId";
    private static final String REFERRAL_NOT_FOUND_WITH_ID = "obgynReferral.notFoundWithId";
    private static final String REFERRAL_ALREADY_CLOSED = "obgynReferral.closed";
    private static final String ATTACHMENT_REQUIRED_FOR_HIGH_URGENCY = "obgynReferral.attachment.requiredForHighUrgency";

    private final ObgynReferralRepository referralRepository;
    private final ObgynReferralMessageRepository messageRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRepository userRepository;
    private final ObgynReferralMapper referralMapper;
    private final ObjectMapper objectMapper;

    @Override
    public ObgynReferralResponseDTO createReferral(ObgynReferralCreateRequestDTO request, String username) {
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException(PATIENT_NOT_FOUND_WITH_ID, request.getPatientId()));

        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(HOSPITAL_NOT_FOUND_WITH_ID, request.getHospitalId()));

        User midwife = resolveUserByUsername(username);

        validateAttachmentsForUrgency(request);

        ObgynReferral referral = ObgynReferral.builder()
            .patient(patient)
            .hospital(hospital)
            .midwife(midwife)
            .gestationalAgeWeeks(request.getGestationalAgeWeeks())
            .careContext(request.getCareContext())
            .referralReason(request.getReferralReason())
            .clinicalIndication(request.getClinicalIndication())
            .urgency(request.getUrgency())
            .historySummary(request.getHistorySummary())
            .ongoingMidwiferyCare(request.isOngoingMidwiferyCare())
            .transferType(request.getTransferType())
            .status(ObgynReferralStatus.SUBMITTED)
            .slaDueAt(calculateSlaDueAt(request.getUrgency()))
            .patientContactSnapshot(buildPatientSnapshot(patient, hospital))
            .midwifeContactSnapshot(buildUserSnapshot(midwife))
            .createdBy(username)
            .updatedBy(username)
            .build();

        applyAttachments(referral, request.getAttachments(), midwife);

        ObgynReferral saved = referralRepository.save(referral);
        log.info("Created OB-GYN referral {} for patient {}", saved.getId(), patient.getId());
        return referralMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ObgynReferralResponseDTO getReferral(UUID referralId) {
        ObgynReferral referral = findReferral(referralId);
        return referralMapper.toResponseDTO(referral);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ObgynReferralResponseDTO> getReferralsForPatient(UUID patientId, Pageable pageable) {
        return referralRepository.findByPatient_Id(patientId, pageable)
            .map(referralMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ObgynReferralResponseDTO> getReferralsForHospital(UUID hospitalId, Pageable pageable) {
        return referralRepository.findByHospital_Id(hospitalId, pageable)
            .map(referralMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ObgynReferralResponseDTO> getReferralsForObgyn(UUID obgynUserId, Pageable pageable) {
        return referralRepository.findByObgyn_Id(obgynUserId, pageable)
            .map(referralMapper::toResponseDTO);
    }

    @Override
    public ObgynReferralResponseDTO acknowledgeReferral(
        UUID referralId,
        ObgynReferralAcknowledgeRequestDTO request,
        String username
    ) {
        ObgynReferral referral = findReferral(referralId);
        ensureEditable(referral);

        if (request.getObgynUserId() == null) {
            throw new BusinessException("obgynReferral.acknowledge.obgynRequired");
        }

        User assignedObgyn = userRepository.findByIdWithRolesAndProfiles(request.getObgynUserId())
            .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_WITH_ID, request.getObgynUserId()));

        referral.setObgyn(assignedObgyn);
        referral.setPlanSummary(request.getPlanSummary());
        referral.setAcknowledgementTimestamp(LocalDateTime.now());
        referral.setStatus(ObgynReferralStatus.ACKNOWLEDGED);
        referral.setUpdatedBy(username);

        log.info("Referral {} acknowledged by {}", referralId, assignedObgyn.getUsername());
        return referralMapper.toResponseDTO(referral);
    }

    @Override
    public ObgynReferralResponseDTO completeReferral(
        UUID referralId,
        ObgynReferralCompletionRequestDTO request,
        String username
    ) {
        ObgynReferral referral = findReferral(referralId);
        ensureEditable(referral);

        referral.setPlanSummary(request.getPlanSummary());
        referral.setCompletionTimestamp(LocalDateTime.now());
        referral.setStatus(ObgynReferralStatus.COMPLETED);
        referral.setUpdatedBy(username);

        if (request.isUpdateCareTeam()) {
            referral.setCareTeamUpdatedAt(LocalDateTime.now());
        }

        log.info("Referral {} marked complete by {}", referralId, username);
        return referralMapper.toResponseDTO(referral);
    }

    @Override
    public ObgynReferralResponseDTO cancelReferral(
        UUID referralId,
        ObgynReferralCancelRequestDTO request,
        String username
    ) {
        ObgynReferral referral = findReferral(referralId);
        ensureEditable(referral);

        referral.setCancellationReason(request.getReason());
        referral.setCancelledTimestamp(LocalDateTime.now());
        referral.setStatus(ObgynReferralStatus.CANCELLED);
        referral.setUpdatedBy(username);

        log.info("Referral {} cancelled by {}", referralId, username);
        return referralMapper.toResponseDTO(referral);
    }

    @Override
    public ObgynReferralMessageDTO addMessage(
        UUID referralId,
        ObgynReferralMessageRequestDTO request,
        String username
    ) {
        ObgynReferral referral = findReferral(referralId);
        ensureNotCancelled(referral);

        User sender = resolveUserByUsername(username);

        List<ObgynReferralMessageDTO.MessageAttachmentDTO> attachmentPayload =
            mapMessageAttachmentPayload(request.getAttachments());

        ObgynReferralMessage message = ObgynReferralMessage.builder()
            .referral(referral)
            .sender(sender)
            .body(request.getBody())
            .attachmentsJson(referralMapper.serializeMessageAttachments(attachmentPayload))
            .build();

        ObgynReferralMessage saved = messageRepository.save(message);
        referral.addMessage(saved);

        log.debug("Referral {} new message persisted by {}", referralId, username);
        return referralMapper.toMessageDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ObgynReferralMessageDTO> getMessages(UUID referralId) {
        findReferral(referralId); // ensure referral exists
        return messageRepository.findByReferral_IdOrderBySentAtAsc(referralId).stream()
            .map(referralMapper::toMessageDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReferralStatusSummaryDTO getStatusSummary() {
        LocalDateTime now = LocalDateTime.now();

        long submitted = referralRepository.countByStatus(ObgynReferralStatus.SUBMITTED);
        long acknowledged = referralRepository.countByStatus(ObgynReferralStatus.ACKNOWLEDGED);
        long inProgress = referralRepository.countByStatus(ObgynReferralStatus.IN_PROGRESS);
        long completed = referralRepository.countByStatus(ObgynReferralStatus.COMPLETED);
        long cancelled = referralRepository.countByStatus(ObgynReferralStatus.CANCELLED);
        long overdue = referralRepository.countByStatusAndSlaDueAtBefore(ObgynReferralStatus.SUBMITTED, now)
            + referralRepository.countByStatusAndSlaDueAtBefore(ObgynReferralStatus.ACKNOWLEDGED, now);

        return ReferralStatusSummaryDTO.builder()
            .submitted(submitted)
            .acknowledged(acknowledged)
            .inProgress(inProgress)
            .completed(completed)
            .cancelled(cancelled)
            .overdue(overdue)
            .build();
    }

    private void validateAttachmentsForUrgency(ObgynReferralCreateRequestDTO request) {
        if (request.getUrgency() == ObgynReferralUrgency.ROUTINE) {
            return;
        }

        if (CollectionUtils.isEmpty(request.getAttachments())) {
            throw new BusinessException(ATTACHMENT_REQUIRED_FOR_HIGH_URGENCY);
        }
    }

    private ObgynReferral findReferral(UUID referralId) {
        return referralRepository.findById(referralId)
            .orElseThrow(() -> new ResourceNotFoundException(REFERRAL_NOT_FOUND_WITH_ID, referralId));
    }

    private User resolveUserByUsername(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new ResourceNotFoundException(USER_NOT_FOUND_WITH_USERNAME, username));
    }

    private void ensureEditable(ObgynReferral referral) {
        if (referral.getStatus() == ObgynReferralStatus.CANCELLED
            || referral.getStatus() == ObgynReferralStatus.COMPLETED) {
            throw new BusinessException(REFERRAL_ALREADY_CLOSED);
        }
    }

    private void ensureNotCancelled(ObgynReferral referral) {
        if (referral.getStatus() == ObgynReferralStatus.CANCELLED) {
            throw new BusinessException(REFERRAL_ALREADY_CLOSED);
        }
    }

    private LocalDateTime calculateSlaDueAt(ObgynReferralUrgency urgency) {
        LocalDateTime now = LocalDateTime.now();
        return switch (urgency) {
            case URGENT -> now.plusHours(6);
            case PRIORITY -> now.plusHours(24);
            case ROUTINE -> now.plusHours(72);
        };
    }

    private void applyAttachments(
        ObgynReferral referral,
        List<ReferralAttachmentUploadDTO> uploads,
        User uploader
    ) {
        if (CollectionUtils.isEmpty(uploads)) {
            return;
        }

        uploads.stream()
            .filter(Objects::nonNull)
            .forEach(upload -> {
                ReferralAttachment attachment = ReferralAttachment.builder()
                    .storageKey(upload.getTempFileId())
                    .displayName(StringUtils.hasText(upload.getDisplayName())
                        ? upload.getDisplayName()
                        : upload.getTempFileId())
                    .category(upload.getCategory())
                    .contentType(upload.getContentType())
                    .sizeBytes(upload.getSizeBytes())
                    .uploadedBy(uploader)
                    .build();
                referral.addAttachment(attachment);
            });
    }

    private String buildPatientSnapshot(Patient patient, Hospital hospital) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", patient.getId());
        snapshot.put("mrn", hospital != null ? patient.getMrnForHospital(hospital.getId()) : null);
        snapshot.put("firstName", patient.getFirstName());
        snapshot.put("lastName", patient.getLastName());
        snapshot.put("dateOfBirth", patient.getDateOfBirth());
        snapshot.put("phone", patient.getPhoneNumberPrimary());
        snapshot.put("email", patient.getEmail());
        snapshot.put("hospitalId", hospital != null ? hospital.getId() : null);
        return toJson(snapshot);
    }

    private String buildUserSnapshot(User user) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", user.getId());
        snapshot.put("username", user.getUsername());
        snapshot.put("firstName", user.getFirstName());
        snapshot.put("lastName", user.getLastName());
        snapshot.put("phone", user.getPhoneNumber());
        snapshot.put("primaryRole", user.getUserRoles().stream()
            .map(link -> link.getRole() != null ? link.getRole().getCode() : null)
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse(null));
        return toJson(snapshot);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize referral snapshot", e);
        }
    }

    private List<ObgynReferralMessageDTO.MessageAttachmentDTO> mapMessageAttachmentPayload(
        List<ObgynReferralMessageRequestDTO.MessageAttachmentDTO> attachments
    ) {
        if (CollectionUtils.isEmpty(attachments)) {
            return List.of();
        }

        return attachments.stream()
            .filter(Objects::nonNull)
            .map(attachment -> ObgynReferralMessageDTO.MessageAttachmentDTO.builder()
                .storageKey(attachment.getTempFileId())
                .displayName(attachment.getDisplayName())
                .contentType(attachment.getContentType())
                .sizeBytes(attachment.getSizeBytes())
                .build())
            .toList();
    }
}
