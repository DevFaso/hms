package com.example.hms.payload.dto;

import java.time.LocalDate;

/**
 * Describes a single date that was skipped during a bulk-scheduling operation.
 *
 * @param date   The calendar date that was not scheduled.
 * @param reason Human-readable explanation (e.g. "Shift overlap", "Staff on leave").
 */
public record BulkShiftSkipDTO(
    LocalDate date,
    String reason
) { }
