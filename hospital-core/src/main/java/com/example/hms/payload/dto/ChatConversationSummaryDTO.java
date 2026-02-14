package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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

