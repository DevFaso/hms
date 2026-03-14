package com.example.hms.payload.dto.clinical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO for a single clinical inbox item (messages, results, consults, etc.)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClinicalInboxItemDTO {

    private UUID id;
    private String category;   // MESSAGE, CRITICAL_RESULT, CONSULT_REQUEST, REFILL_REQUEST, DOCUMENT_TO_SIGN, TASK
    private String source;
    private String patientName;
    private UUID patientId;
    private String subject;
    private String urgency;    // LOW, NORMAL, HIGH, CRITICAL
    private LocalDateTime timestamp;
    private String actionType; // REVIEW, SIGN, REPLY, ACCEPT, DECLINE, OPEN_CHART
}
