package com.example.hms.payload.dto;

import com.example.hms.enums.StaffShiftStatus;
import com.example.hms.enums.StaffShiftType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

public record StaffShiftResponseDTO(
    UUID id,
    UUID staffId,
    String staffName,
    String staffRole,
    UUID hospitalId,
    String hospitalName,
    UUID departmentId,
    String departmentName,
    /** The calendar date the shift starts (always the start-day anchor). */
    LocalDate shiftDate,
    LocalTime startTime,
    LocalTime endTime,
    /**
     * True when endTime &lt; startTime, meaning the shift crosses midnight
     * and ends on {@link #shiftEndDate} (shiftDate + 1 day).
     */
    boolean crossMidnight,
    /**
     * The calendar date the shift ends. Equals {@link #shiftDate} for same-day shifts
     * and shiftDate + 1 for cross-midnight shifts.
     */
    LocalDate shiftEndDate,
    StaffShiftType shiftType,
    StaffShiftStatus status,
    boolean published,
    String notes,
    String cancellationReason,
    UUID scheduledByUserId,
    String scheduledByName,
    UUID lastModifiedByUserId,
    String lastModifiedByName,
    LocalDateTime statusChangedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) { }
