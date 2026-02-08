package com.example.hms.enums;

/**
 * Lifecycle phases for cross-modality imaging reports.
 */
public enum ImagingReportStatus {
    /** Initial draft captured by technologist or auto-ingested from PACS. */
    DRAFT,
    /** Preliminary read shared with care team while awaiting final sign-off. */
    PRELIMINARY,
    /** Final signed interpretation delivered to ordering provider. */
    FINAL,
    /** Follow-up edit applied after finalization without invalidating the prior version. */
    ADDENDUM,
    /** Corrected report that supersedes a previous version due to substantive changes. */
    CORRECTED,
    /** Amended report reflecting minor adjustments that do not require a new accession. */
    AMENDED,
    /** Report voided or rescinded; typically when order is cancelled mid-flight. */
    CANCELLED,
    /** Report rejected due to validation or ingestion failures. */
    ERROR
}
