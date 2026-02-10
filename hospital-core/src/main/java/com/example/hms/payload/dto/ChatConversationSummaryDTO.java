package com.example.hms.payload.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatConversationSummaryDTO {

    private UUID conversationUserId;
    private String conversationUserName;
    private String lastMessageContent;
    private LocalDateTime lastMessageTimestamp;
    private UUID hospitalId;
    private boolean lastMessageRead;
    private int unreadCount;
}

