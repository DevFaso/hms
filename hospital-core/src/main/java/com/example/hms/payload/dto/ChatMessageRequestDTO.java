package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class ChatMessageRequestDTO {
    /** Recipient email – used by hospital-context sends. Optional if recipientId is set. */
    private String recipientEmail;

    /** Hospital name – used by hospital-context sends. Optional for SUPER_ADMIN. */
    private String hospitalName;

    /**
     * Message body. May be blank or null for attachment-only sends; the service enforces
     * "content-or-attachments required" so an empty message with no attachments is rejected.
     */
    private String content;

    private String roleCode;

    /** Recipient UUID – alternative to recipientEmail. */
    private UUID recipientId;

    /**
     * Optional telehealth attachments. Each entry must carry the {@code storageKey}
     * returned by {@code POST /files/chat-attachments}; service re-resolves and
     * persists them under the new message. Capped at 4 by the service.
     */
    private List<ChatAttachmentDTO> attachments;

    /**
     * QA D1349 (round 30): the service's two rules (a recipient; content or
     * attachments), checked at the edge too, so both are named in one answer
     * with any malformed value of the body.
     */
    @jakarta.validation.constraints.AssertTrue(message = "{chat.recipient.required}")
    @com.example.hms.validation.ReportedOn("recipientId")
    @com.fasterxml.jackson.annotation.JsonIgnore
    @io.swagger.v3.oas.annotations.Hidden
    public boolean isRecipientNamed() {
        return recipientId != null || (recipientEmail != null && !recipientEmail.isBlank());
    }

    /** See {@link #isRecipientNamed()}. */
    @jakarta.validation.constraints.AssertTrue(message = "{chat.message.empty}")
    @com.example.hms.validation.ReportedOn("content")
    @com.fasterxml.jackson.annotation.JsonIgnore
    @io.swagger.v3.oas.annotations.Hidden
    public boolean isContentOrAttachmentGiven() {
        return (content != null && !content.isBlank()) || (attachments != null && !attachments.isEmpty());
    }
}
