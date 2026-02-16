package com.example.hms.payload.dto;

import com.example.hms.enums.StaffLeaveStatus;
import com.example.hms.enums.StaffLeaveType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

public record StaffLeaveResponseDTO(
    UUID id,
    UUID staffId,
    String staffName,
    String staffRole,
    UUID hospitalId,
    String hospitalName,
    UUID departmentId,
    String departmentName,
    StaffLeaveType leaveType,
    StaffLeaveStatus status,
    LocalDate startDate,
    LocalDate endDate,
    LocalTime startTime,
    LocalTime endTime,
    boolean requiresCoverage,
    String reason,
    String managerNote,
    UUID requestedByUserId,
    String requestedByName,
    UUID reviewedByUserId,
    String reviewedByName,
    LocalDateTime reviewedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) { }
