package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for Inbox Counts
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxCountsDTO {

    private Integer unreadMessages;

    private Integer pendingRefills;

    private Integer pendingResults;

    private Integer tasksToComplete;

    private Integer documentsToSign;
}
