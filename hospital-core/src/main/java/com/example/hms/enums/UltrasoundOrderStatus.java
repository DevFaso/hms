package com.example.hms.enums;

/**
 * Status of an ultrasound order throughout its lifecycle.
 */
public enum UltrasoundOrderStatus {
    /**
     * Order has been created but not yet scheduled.
     */
    ORDERED,

    /**
     * Appointment has been scheduled for the ultrasound.
     */
    SCHEDULED,

    /**
     * Ultrasound scan is in progress.
     */
    IN_PROGRESS,

    /**
     * Scan completed, awaiting report.
     */
    COMPLETED,

    /**
     * Report has been finalized and is available for review.
     */
    REPORT_AVAILABLE,

    /**
     * Order was cancelled before completion.
     */
    CANCELLED
}
