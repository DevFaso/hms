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

        // Sender Info
        User sender = message.getSender();
        if (sender != null) {
            String senderName = (sender.getFirstName() != null ? sender.getFirstName() : "") +
                               (sender.getLastName() != null ? " " + sender.getLastName() : "");
            dto.setSenderName(senderName.trim());
            // Get primary role from userRoles
            String senderRole = sender.getUserRoles() != null && !sender.getUserRoles().isEmpty()
                ? sender.getUserRoles().iterator().next().getRole().getName()
                : null;
            dto.setSenderRole(senderRole);
        }

        // Recipient Info
        User recipient = message.getRecipient();
        if (recipient != null) {
            String recipientName = (recipient.getFirstName() != null ? recipient.getFirstName() : "") +
                                   (recipient.getLastName() != null ? " " + recipient.getLastName() : "");
            dto.setRecipientName(recipientName.trim());
            // Get primary role from userRoles
            String recipientRole = recipient.getUserRoles() != null && !recipient.getUserRoles().isEmpty()
                ? recipient.getUserRoles().iterator().next().getRole().getName()
                : null;
            dto.setRecipientRole(recipientRole);
        }

        // Hospital Info from assignment
        if (message.getAssignment() != null && message.getAssignment().getHospital() != null) {
            dto.setHospitalName(message.getAssignment().getHospital().getName());
        }

        // Department name for recipient (if available)
        if (recipient != null && recipient.getStaffProfile() != null && recipient.getStaffProfile().getDepartment() != null) {
            dto.setRecipientDepartmentName(recipient.getStaffProfile().getDepartment().getName());
        }

        return dto;
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
