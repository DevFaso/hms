package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessageResponseDTO {

    private String id;
    private String timestamp;
    private String senderId;
    private String senderName;
    private String senderRole;
    private String senderProfilePictureUrl;
    private String recipientId;
    private String recipientName;
    private String recipientRole;
    private String recipientProfilePictureUrl;
    private String recipientDepartmentName;
    private String hospitalName;
    private String content;
    private boolean read;
    private String translation;
}
