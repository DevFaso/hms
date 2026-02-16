package com.example.hms.payload.dto;

import com.example.hms.enums.StaffShiftType;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record StaffShiftRequestDTO(
    @NotNull UUID staffId,
    @NotNull UUID hospitalId,
    UUID departmentId,
    @NotNull @FutureOrPresent LocalDate shiftDate,
    @NotNull LocalTime startTime,
    @NotNull LocalTime endTime,
    @NotNull StaffShiftType shiftType,
    String notes
) { }
