package com.example.hms.payload.dto.safeguarding;

import com.example.hms.enums.SafeguardingReviewStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * r53: one safeguarding review, for the treating team and the administrators of its hospital only
 * (SafeguardingReviewController). Never part of any other response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafeguardingReviewDTO {
    private UUID id;
    private UUID patientId;
    private String patientName;
    private String triggerCode;
    private String sourceType;
    private Integer patientAgeYears;
    /** The age threshold in force when the review opened: a provisional value pending local approval. */
    private Integer ageThresholdYears;
    /** Always true while the rule is provisional (pending the obstetric and child-protection leads' approval). */
    private boolean provisionalRule;
    private SafeguardingReviewStatus status;
    private LocalDateTime raisedAt;
    private String raisedByName;
    private LocalDateTime reviewedAt;
    private String reviewedByName;
    private String reviewNote;
}
