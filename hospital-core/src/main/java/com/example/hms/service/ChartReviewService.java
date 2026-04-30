package com.example.hms.service;

import com.example.hms.payload.dto.chartreview.ChartReviewDTO;

import java.util.UUID;

/**
 * Aggregates the data shown in the Chart Review tabbed viewer
 * (Encounters / Notes / Results / Medications / Imaging / Procedures
 * + a unified timeline). The service is read-only; all source data
 * lives in the existing clinical tables.
 */
public interface ChartReviewService {

    /** Hard cap on the per-section row count, regardless of caller-supplied limit. */
    int MAX_LIMIT = 100;
    /** Default when the caller does not supply a limit. */
    int DEFAULT_LIMIT = 20;
    /** Floor applied to caller-supplied limit values. */
    int MIN_LIMIT = 5;

    /**
     * Build the chart-review payload for a patient, optionally scoped to a hospital.
     *
     * @param patientId  patient whose chart is being viewed
     * @param hospitalId hospital scope (may be {@code null} to read across the patient's
     *                   registered hospitals — mirrors the Storyboard endpoint)
     * @param limit      requested per-section row cap; clamped to [{@link #MIN_LIMIT},
     *                   {@link #MAX_LIMIT}]; {@code null} or zero falls back to
     *                   {@link #DEFAULT_LIMIT}
     * @return populated DTO; never {@code null}
     */
    ChartReviewDTO getChartReview(UUID patientId, UUID hospitalId, Integer limit);
}
