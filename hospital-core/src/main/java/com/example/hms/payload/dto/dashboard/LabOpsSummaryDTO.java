package com.example.hms.payload.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Lab Operations Dashboard summary for lab managers, directors and hospital admins.
 *
 * <p>Provides a real-time snapshot of lab order throughput, status breakdown,
 * priority distribution, and average turnaround time — scoped to the user's
 * active hospital.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabOpsSummaryDTO {

    private UUID hospitalId;
    private LocalDate asOfDate;

    // ── Overall counts (today) ──────────────────────────────────────────────

    /** Total lab orders placed today. */
    private long ordersToday;

    /** Lab orders completed today. */
    private long completedToday;

    /** Lab orders cancelled today. */
    private long cancelledToday;

    // ── Rolling window counts ───────────────────────────────────────────────

    /** Total lab orders this week (Mon–today). */
    private long ordersThisWeek;

    /** Lab orders completed this week. */
    private long completedThisWeek;

    /** Total lab orders this month. */
    private long ordersThisMonth;

    // ── Status breakdown (current / all-time active) ────────────────────────

    /** Orders currently in ORDERED status. */
    private long statusOrdered;

    /** Orders currently in PENDING status. */
    private long statusPending;

    /** Orders currently in COLLECTED status. */
    private long statusCollected;

    /** Orders currently in RECEIVED status. */
    private long statusReceived;

    /** Orders currently in IN_PROGRESS status. */
    private long statusInProgress;

    /** Orders currently in RESULTED status. */
    private long statusResulted;

    /** Orders currently in VERIFIED status. */
    private long statusVerified;

    // ── Priority breakdown (active orders only) ─────────────────────────────

    /** Active orders with ROUTINE priority. */
    private long priorityRoutine;

    /** Active orders with URGENT priority. */
    private long priorityUrgent;

    /** Active orders with STAT priority. */
    private long priorityStat;

    // ── Turnaround time ─────────────────────────────────────────────────────

    /**
     * Average turnaround time in minutes for orders completed today
     * (null when no completed orders today).
     */
    private Double avgTurnaroundMinutesToday;

    /**
     * Average turnaround time in minutes for orders completed this week
     * (null when no completed orders this week).
     */
    private Double avgTurnaroundMinutesThisWeek;

    // ── Aging ───────────────────────────────────────────────────────────────

    /** Active orders older than 24 hours (potential bottlenecks). */
    private long ordersOlderThan24h;
}
