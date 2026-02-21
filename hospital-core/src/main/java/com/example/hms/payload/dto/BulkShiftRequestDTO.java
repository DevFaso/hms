package com.example.hms.payload.dto;

import com.example.hms.enums.StaffShiftType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

/**
 * Request body for bulk-scheduling recurring shifts over a date range.
 *
 * <p>Example: schedule Mon–Fri 6:00 PM–1:00 AM NIGHT shifts for a staff member
 * between 2026-02-23 and 2026-03-21 with {@code skipConflicts=true} so that
 * dates with pre-existing shifts are silently skipped instead of aborting the
 * entire batch.
 */
public record BulkShiftRequestDTO(

    @NotNull
    @Schema(description = "Staff member to schedule", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    UUID staffId,

    @NotNull
    @Schema(description = "Hospital context", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    UUID hospitalId,

    @Schema(description = "Optional department override; defaults to the staff member's own department")
    UUID departmentId,

    @NotNull
    @FutureOrPresent
    @Schema(description = "First date to consider (inclusive). Must be today or in the future.", example = "2026-02-23")
    LocalDate startDate,

    @NotNull
    @Schema(description = "Last date to consider (inclusive). Must be >= startDate.", example = "2026-03-21")
    LocalDate endDate,

    @NotEmpty
    @Schema(description = "Days of the week to create shifts on. E.g. [MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY]")
    Set<DayOfWeek> daysOfWeek,

    @NotNull
    @Schema(description = "Shift start time (HH:mm)", example = "18:00")
    LocalTime startTime,

    @NotNull
    @Schema(description = "Shift end time (HH:mm). May be before startTime for cross-midnight shifts.", example = "01:00")
    LocalTime endTime,

    @NotNull
    @Schema(description = "Shift type classification", example = "NIGHT")
    StaffShiftType shiftType,

    @Schema(description = "Optional notes applied to every generated shift")
    String notes,

    @Schema(description = "When true, dates that would produce a conflict (leave, overlap, day-off) are "
        + "silently skipped and recorded in the 'skipped' list instead of aborting the whole batch.",
        defaultValue = "true")
    boolean skipConflicts
) { }
