package com.example.hms.payload.dto.nurse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NurseVitalTaskResponseDTO {

    private UUID id;
    private UUID patientId;
    private String patientName;
    private String type;
    private LocalDateTime dueTime;
    private boolean overdue;
    /** QA D207: the NEWS2 total of the last set, null when none was recorded. */
    private Integer news2Score;
    /**
     * Policy 2026-11-20 (provisional): for a woman in labour or within 42 days of a birth here, the
     * maternal early-warning total of the last set and its level (LOW, LOW_MEDIUM, MEDIUM, HIGH); the
     * NEWS2 total is then never shown.
     */
    private Integer mewsScore;
    private String mewsTier;
}
