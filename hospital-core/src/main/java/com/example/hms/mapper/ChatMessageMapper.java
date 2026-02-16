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
        populateRecipientInfo(dto, message.getRecipient());

        if (message.getAssignment() != null && message.getAssignment().getHospital() != null) {
            dto.setHospitalName(message.getAssignment().getHospital().getName());
        }

        return dto;
    }

    private void populateSenderInfo(ChatMessageResponseDTO dto, User sender) {
        if (sender == null) return;
        dto.setSenderName(resolveFullName(sender));
        dto.setSenderRole(resolvePrimaryRole(sender));
    }

    private void populateRecipientInfo(ChatMessageResponseDTO dto, User recipient) {
        if (recipient == null) return;
        dto.setRecipientName(resolveFullName(recipient));
        dto.setRecipientRole(resolvePrimaryRole(recipient));
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
