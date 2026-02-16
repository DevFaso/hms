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
    LocalDate shiftDate,
    LocalTime startTime,
    LocalTime endTime,
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
