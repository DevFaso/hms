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
    /** QA D1075: when the handoff was reported; updatedAt moves when it is completed. */
    private LocalDateTime createdAt;
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
    /** QA D776: who the handoff is addressed to (null on legacy rows). */
    private UUID recipientUserId;
    private String recipientName;
    /** QA D776: true when the caller may complete it (the recipient; anyone but the author on a legacy row). */
    private Boolean completableByMe;
}
