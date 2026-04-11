package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO representing a single In-Basket item for the provider notification centre.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InBasketItemDTO {

    private UUID id;
    private String itemType;     // RESULT, ORDER, MESSAGE, TASK
    private String priority;     // NORMAL, URGENT, CRITICAL
    private String status;       // UNREAD, READ, ACKNOWLEDGED
    private String title;
    private String body;

    private UUID referenceId;
    private String referenceType; // LAB_RESULT, IMAGING_REPORT

    private UUID encounterId;
    private UUID patientId;
    private String patientName;
    private String orderingProviderName;

    private LocalDateTime createdAt;
    private LocalDateTime readAt;
    private LocalDateTime acknowledgedAt;
}
