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
        dto.setTimestamp(message.getTimestamp() != null ? message.getTimestamp().toString() : null);
        dto.setRead(message.isRead());

        populateSenderInfo(dto, message.getSender());
        populateRecipientInfo(dto, message);

        // Hospital Info from assignment
        if (message.getAssignment() != null && message.getAssignment().getHospital() != null) {
            dto.setHospitalName(message.getAssignment().getHospital().getName());
        }

        return dto;
    }

    private void populateSenderInfo(ChatMessageResponseDTO dto, User sender) {
        if (sender == null) return;
        dto.setSenderName(buildUserFullName(sender));
        dto.setSenderRole(extractPrimaryRole(sender));
    }

    private void populateRecipientInfo(ChatMessageResponseDTO dto, ChatMessage message) {
        User recipient = message.getRecipient();
        if (recipient == null) return;
        dto.setRecipientName(buildUserFullName(recipient));
        dto.setRecipientRole(extractPrimaryRole(recipient));
        if (recipient.getStaffProfile() != null && recipient.getStaffProfile().getDepartment() != null) {
            dto.setRecipientDepartmentName(recipient.getStaffProfile().getDepartment().getName());
        }
    }

    private String buildUserFullName(User user) {
        String first = user.getFirstName() != null ? user.getFirstName() : "";
        String last = user.getLastName() != null ? " " + user.getLastName() : "";
        return (first + last).trim();
    }

    private String extractPrimaryRole(User user) {
        if (user.getUserRoles() == null || user.getUserRoles().isEmpty()) {
            return null;
        }
        return user.getUserRoles().iterator().next().getRole().getName();
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
