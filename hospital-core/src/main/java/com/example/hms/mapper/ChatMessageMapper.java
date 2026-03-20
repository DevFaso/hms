package com.example.hms.mapper;

import com.example.hms.model.ChatMessage;
import com.example.hms.model.User;
import com.example.hms.payload.dto.ChatMessageRequestDTO;
import com.example.hms.payload.dto.ChatMessageResponseDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class ChatMessageMapper {

    public ChatMessageResponseDTO toChatMessageResponseDTO(ChatMessage message) {
        if (message == null) return null;

        ChatMessageResponseDTO dto = new ChatMessageResponseDTO();
        dto.setId(message.getId() != null ? message.getId().toString() : null);
        dto.setContent(message.getContent());

        // sentAt is the authoritative delivery time; fall back to legacy timestamp field
        LocalDateTime ts = message.getSentAt() != null ? message.getSentAt() : message.getTimestamp();
        dto.setTimestamp(ts != null ? ts.toString() : null);

        dto.setRead(message.isRead());

        populateSenderInfo(dto, message.getSender());
        populateRecipientInfo(dto, message.getRecipient());

        // Resolve hospitalName from the assignment context (null for context-free messages)
        if (message.getAssignment() != null && message.getAssignment().getHospital() != null) {
            dto.setHospitalName(message.getAssignment().getHospital().getName());
        }

        // Map optional attachment fields
        dto.setAttachmentUrl(message.getAttachmentUrl());
        dto.setAttachmentName(message.getAttachmentName());
        dto.setAttachmentContentType(message.getAttachmentContentType());
        dto.setAttachmentSizeBytes(message.getAttachmentSizeBytes());

        return dto;
    }

    private void populateSenderInfo(ChatMessageResponseDTO dto, User sender) {
        if (sender == null) return;
        dto.setSenderId(sender.getId() != null ? sender.getId().toString() : null);
        dto.setSenderName(resolveFullName(sender));
        dto.setSenderRole(resolvePrimaryRole(sender));
        dto.setSenderProfilePictureUrl(sender.getProfileImageUrl());
    }

    private void populateRecipientInfo(ChatMessageResponseDTO dto, User recipient) {
        if (recipient == null) return;
        dto.setRecipientId(recipient.getId() != null ? recipient.getId().toString() : null);
        dto.setRecipientName(resolveFullName(recipient));
        dto.setRecipientRole(resolvePrimaryRole(recipient));
        dto.setRecipientProfilePictureUrl(recipient.getProfileImageUrl());
        if (recipient.getStaffProfile() != null && recipient.getStaffProfile().getDepartment() != null) {
            dto.setRecipientDepartmentName(recipient.getStaffProfile().getDepartment().getName());
        }
    }

    private String resolveFullName(User user) {
        String name = (user.getFirstName() != null ? user.getFirstName() : "") +
                      (user.getLastName() != null ? " " + user.getLastName() : "");
        return name.trim();
    }

    private String resolvePrimaryRole(User user) {
        return user.getUserRoles() != null && !user.getUserRoles().isEmpty()
            ? user.getUserRoles().iterator().next().getRole().getName()
            : null;
    }

    public ChatMessage toChatMessage(ChatMessageRequestDTO dto, User sender, User recipient) {
        if (dto == null) return null;

        return ChatMessage.builder()
                .sender(sender)
                .recipient(recipient)
                .content(dto.getContent())
                .timestamp(LocalDateTime.now())
                .read(false)
                .build();
    }
}
