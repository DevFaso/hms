package com.example.hms.payload.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Dashboard summary for ROLE_QUALITY_MANAGER.
 *
 * <p>Covers: QA review queue depth, validation study pipeline metrics,
 * non-conformance counts and quality pass rate — scoped to the manager's
 * active hospital.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityManagerDashboardDTO {

    private UUID hospitalId;
    private LocalDate asOfDate;

    // ── QA Review Queue ──────────────────────────────────────────────────────

    /** Definitions currently awaiting QA review (the primary queue). */
    private long pendingQaReview;

    /** Definitions in DRAFT — not yet submitted to QA. */
    private long draftDefinitions;

    /** Definitions that passed through QA and are now awaiting director sign-off. */
    private long pendingDirectorApproval;

    /** Definitions fully approved and active. */
    private long activeDefinitions;

    // ── Validation Study Pipeline ────────────────────────────────────────────

    /** Total validation studies on record for this hospital. */
    private long totalValidationStudies;

    /** Validation studies that passed. */
    private long passedValidationStudies;

    /** Validation studies that failed. */
    private long failedValidationStudies;

    /**
     * Quality pass rate as a percentage (0-100), or null when no studies exist.
     * Formula: passedValidationStudies / (passedValidationStudies + failedValidationStudies) * 100
     */
    private Double qualityPassRate;

    /** Studies recorded in the last 30 days. */
    private long validationStudiesLast30Days;

    // ── Non-Conformance (Lab Orders) ─────────────────────────────────────────

    /** Lab orders cancelled this week — non-conformance proxy. */
    private long ordersCancelledThisWeek;

    /** Lab orders received today. */
    private long ordersToday;
}
