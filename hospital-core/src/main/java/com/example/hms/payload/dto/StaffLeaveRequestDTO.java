package com.example.hms.payload.dto;

import com.example.hms.enums.StaffLeaveType;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record StaffLeaveRequestDTO(
    @NotNull UUID staffId,
    @NotNull UUID hospitalId,
    UUID departmentId,
    @NotNull @FutureOrPresent LocalDate startDate,
    @NotNull @FutureOrPresent LocalDate endDate,
    LocalTime startTime,
    LocalTime endTime,
    @NotNull StaffLeaveType leaveType,
    boolean requiresCoverage,
    String reason
) { }
