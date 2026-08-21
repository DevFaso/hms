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
public class NurseHandoffSummaryDTO {

    private UUID id;
    private UUID patientId;
    private String patientName;
    private String direction;
    private LocalDateTime updatedAt;
    /** Situation line — kept under the legacy name the card UI already renders. */
    private String note;
    private String background;
    private String assessment;
    private String recommendation;
    private String status;
    private String createdByName;
    /**
     * Who closed the handoff and when. Written since P0 #1 shipped but exposed
     * by nothing until the 2026-08-21 reassessment flagged the pair as
     * write-only — a completed handoff's completer was unreadable through any
     * API. Null while PENDING.
     */
    private LocalDateTime completedAt;
    private String completedByName;
}
