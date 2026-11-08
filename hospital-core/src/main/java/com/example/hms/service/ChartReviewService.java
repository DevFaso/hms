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

    /** Largest page of a section, regardless of caller-supplied limit (QA D1380: a page, never the whole). */
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
    default ChartReviewDTO getChartReview(UUID patientId, UUID hospitalId, Integer limit) {
        return getChartReview(patientId, hospitalId, limit, null, 0);
    }

    /**
     * QA D1380 (D1347 class): one page of the chart review. Every section is
     * read whole and counted ({@link ChartReviewDTO#getTotals()}); the page
     * returned is rows {@code [page × limit, page × limit + limit)} of each
     * section, or of {@code section} alone when given (the other lists are
     * then empty). The order is total (date, then id), so paging neither
     * repeats nor skips a row.
     */
    ChartReviewDTO getChartReview(UUID patientId, UUID hospitalId, Integer limit, ChartReviewSection section, int page);

    /**
     * QA D1380: every row of every section, for the printed record
     * ({@code PatientRecordPdfService}), which asked for 200 per section and
     * was silently cut at {@link #MAX_LIMIT}.
     */
    ChartReviewDTO getCompleteChartReview(UUID patientId, UUID hospitalId);

    /** QA D1380: the tab a page is asked for. */
    enum ChartReviewSection { TIMELINE, ENCOUNTERS, NOTES, RESULTS, MEDICATIONS, IMAGING, PROCEDURES }
}
