package com.example.hms.payload.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record StaffAvailabilityRequestDTO(
    @NotNull UUID staffId,
    @NotNull UUID hospitalId,
    @jakarta.validation.constraints.FutureOrPresent LocalDate date,
    LocalTime availableFrom,
    LocalTime availableTo,
    boolean dayOff,
    String note,
    @NotNull UUID departmentId
) { }
