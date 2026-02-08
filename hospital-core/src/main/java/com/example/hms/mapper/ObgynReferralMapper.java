package com.example.hms.mapper;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.referral.ObgynReferral;
import com.example.hms.model.referral.ObgynReferralMessage;
import com.example.hms.model.referral.ReferralAttachment;
import com.example.hms.payload.dto.referral.ObgynReferralMessageDTO;
import com.example.hms.payload.dto.referral.ObgynReferralResponseDTO;
import com.example.hms.payload.dto.referral.ReferralAttachmentDTO;
import com.example.hms.payload.dto.referral.ReferralClinicianSummaryDTO;
import com.example.hms.payload.dto.referral.ReferralHospitalSummaryDTO;
import com.example.hms.payload.dto.referral.ReferralPatientSummaryDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ObgynReferralMapper {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObgynReferralMapper.class);
    private static final TypeReference<List<ObgynReferralMessageDTO.MessageAttachmentDTO>> MESSAGE_ATTACHMENTS_TYPE =
        new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public ObgynReferralResponseDTO toResponseDTO(ObgynReferral referral) {
        if (referral == null) {
            return null;
        }

        return ObgynReferralResponseDTO.builder()
            .id(referral.getId())
            .patient(mapPatient(referral.getPatient(), referral.getHospital()))
            .hospital(mapHospital(referral.getHospital()))
            .midwife(mapClinician(referral.getMidwife()))
            .obgyn(mapClinician(referral.getObgyn()))
            .gestationalAgeWeeks(referral.getGestationalAgeWeeks())
            .careContext(referral.getCareContext())
            .referralReason(referral.getReferralReason())
            .clinicalIndication(referral.getClinicalIndication())
            .urgency(referral.getUrgency())
            .historySummary(referral.getHistorySummary())
            .ongoingMidwiferyCare(referral.isOngoingMidwiferyCare())
            .transferType(referral.getTransferType())
            .attachmentsPresent(referral.isAttachmentsPresent())
            .acknowledgementTimestamp(referral.getAcknowledgementTimestamp())
            .planSummary(referral.getPlanSummary())
            .completionTimestamp(referral.getCompletionTimestamp())
            .cancelledTimestamp(referral.getCancelledTimestamp())
            .cancellationReason(referral.getCancellationReason())
            .status(referral.getStatus())
            .slaDueAt(referral.getSlaDueAt())
            .careTeamUpdatedAt(referral.getCareTeamUpdatedAt())
            .letterStoragePath(referral.getLetterStoragePath())
            .letterGeneratedAt(referral.getLetterGeneratedAt())
            .createdAt(referral.getCreatedAt())
            .updatedAt(referral.getUpdatedAt())
            .attachments(mapAttachments(referral.getAttachments()))
            .messages(mapMessages(referral.getMessages()))
            .build();
    }

    public List<ReferralAttachmentDTO> mapAttachments(Set<ReferralAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }

        return attachments.stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(ReferralAttachment::getUploadedAt,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .map(this::toAttachmentDTO)
            .toList();
    }

    public ReferralAttachmentDTO toAttachmentDTO(ReferralAttachment attachment) {
        if (attachment == null) {
            return null;
        }

        User uploader = attachment.getUploadedBy();
        return ReferralAttachmentDTO.builder()
            .id(attachment.getId())
            .storageKey(attachment.getStorageKey())
            .displayName(attachment.getDisplayName())
            .category(attachment.getCategory())
            .contentType(attachment.getContentType())
            .sizeBytes(attachment.getSizeBytes())
            .uploadedBy(uploader != null ? uploader.getId() : null)
            .uploadedByDisplayName(buildDisplayName(uploader))
            .uploadedAt(attachment.getUploadedAt())
            .build();
    }

    public List<ObgynReferralMessageDTO> mapMessages(Set<ObgynReferralMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        return messages.stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(ObgynReferralMessage::getSentAt,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .map(this::toMessageDTO)
            .toList();
    }

    public ObgynReferralMessageDTO toMessageDTO(ObgynReferralMessage message) {
        if (message == null) {
            return null;
        }

        User sender = message.getSender();
        return ObgynReferralMessageDTO.builder()
            .id(message.getId())
            .senderUserId(sender != null ? sender.getId() : null)
            .senderDisplayName(buildDisplayName(sender))
            .body(message.getBody())
            .read(message.isRead())
            .sentAt(message.getSentAt())
            .attachments(deserializeMessageAttachments(message.getAttachmentsJson()))
            .build();
    }

    public String serializeMessageAttachments(List<ObgynReferralMessageDTO.MessageAttachmentDTO> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(attachments);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize referral message attachments", e);
        }
    }

    public List<ObgynReferralMessageDTO.MessageAttachmentDTO> deserializeMessageAttachments(String attachmentsJson) {
        if (!StringUtils.hasText(attachmentsJson)) {
            return List.of();
        }

        try {
            return objectMapper.readValue(attachmentsJson, MESSAGE_ATTACHMENTS_TYPE);
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse referral message attachments payload: {}", e.getMessage());
            return List.of();
        }
    }

    private ReferralPatientSummaryDTO mapPatient(Patient patient, Hospital hospital) {
        if (patient == null) {
            return null;
        }

        return ReferralPatientSummaryDTO.builder()
            .id(patient.getId())
            .mrn(hospital != null ? patient.getMrnForHospital(hospital.getId()) : null)
            .firstName(patient.getFirstName())
            .lastName(patient.getLastName())
            .dateOfBirth(patient.getDateOfBirth())
            .build();
    }

    private ReferralHospitalSummaryDTO mapHospital(Hospital hospital) {
        if (hospital == null) {
            return null;
        }

        return ReferralHospitalSummaryDTO.builder()
            .id(hospital.getId())
            .name(hospital.getName())
            .code(hospital.getCode())
            .build();
    }

    private ReferralClinicianSummaryDTO mapClinician(User user) {
        if (user == null) {
            return null;
        }

        return ReferralClinicianSummaryDTO.builder()
            .userId(user.getId())
            .username(user.getUsername())
            .displayName(buildDisplayName(user))
            .primaryRole(resolvePrimaryRole(user))
            .build();
    }

    private String buildDisplayName(User user) {
        if (user == null) {
            return null;
        }

        String firstName = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String lastName = user.getLastName() != null ? user.getLastName().trim() : "";
        String combined = (firstName + " " + lastName).trim();
        if (!combined.isBlank()) {
            return combined;
        }
        return user.getUsername();
    }

    private String resolvePrimaryRole(User user) {
        if (user == null || user.getUserRoles() == null || user.getUserRoles().isEmpty()) {
            return null;
        }

        return user.getUserRoles().stream()
            .map(UserRole::getRole)
            .filter(Objects::nonNull)
            .map(role -> role.getCode() != null ? role.getCode() : role.getName())
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse(null);
    }
}
