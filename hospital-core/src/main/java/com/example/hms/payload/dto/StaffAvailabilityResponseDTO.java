package com.example.hms.payload.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record StaffAvailabilityResponseDTO(
    UUID id,
    UUID staffId,
    String staffName,
    String staffLicenseNumber,
    UUID hospitalId,
    String hospitalName,
    UUID departmentId,
    String departmentName,
    String departmentTranslationName,
    LocalDate date,
    LocalTime availableFrom,
    LocalTime availableTo,
    boolean dayOff,
    String note
) {}

