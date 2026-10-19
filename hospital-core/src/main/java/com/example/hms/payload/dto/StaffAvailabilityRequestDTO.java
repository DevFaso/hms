package com.example.hms.payload.dto;

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
    Boolean dayOff,
    String note,
    @NotNull UUID departmentId
) {
    // A body that leaves this out or sends null gets the documented default
    // (QA D23): Jackson 3 refuses a null for a primitive record component.
    public StaffAvailabilityRequestDTO {
        if (dayOff == null) {
            dayOff = Boolean.FALSE;
        }
    }
}
