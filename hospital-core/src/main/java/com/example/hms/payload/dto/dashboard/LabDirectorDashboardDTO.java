package com.example.hms.payload.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Dashboard summary for ROLE_LAB_DIRECTOR.
 *
 * <p>Covers: approval queue, validation study pipeline, lab order throughput,
 * average turnaround time and recent audit events — scoped to the director's
 * active hospital.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabDirectorDashboardDTO {

    private UUID hospitalId;
    private LocalDate asOfDate;

    /** Definitions awaiting the Lab Director's final approval. */
    private long pendingDirectorApproval;

    /** Definitions currently in QA review (already past director's concern but visible). */
    private long pendingQaReview;

    /** Definitions in DRAFT state — not yet submitted. */
    private long draftDefinitions;

    /** Total active test definitions. */
    private long activeDefinitions;

    /** Validation studies whose linked definition has PENDING_DIRECTOR_APPROVAL status. */
    private long validationStudiesPendingApproval;

    /** Validation studies recorded in the last 30 days. */
    private long validationStudiesLast30Days;

    /** Lab orders received today (ordered_datetime ≥ today 00:00). */
    private long ordersToday;

    /** Lab orders completed today (status = COMPLETED and in today's range). */
    private long ordersCompletedToday;

    /** Lab orders in IN_PROGRESS or RECEIVED state right now. */
    private long ordersInProgress;

    /** Cancelled lab orders this week — proxy for specimen rejection rate. */
    private long ordersCancelledThisWeek;

    /**
     * Average turnaround time in minutes for orders completed today
     * (null when no completed orders today).
     */
    private Double avgTurnaroundMinutesToday;

    /** Recent approval-related audit events (up to 10). */
    private List<ApprovalAuditSnippet> recentApprovalAudit;

    // ── Nested ──────────────────────────────────────────────────────────────

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalAuditSnippet {
        private UUID definitionId;
        private String testName;
        private String action;
        private String performedBy;
        private String performedAt;
    }
}
