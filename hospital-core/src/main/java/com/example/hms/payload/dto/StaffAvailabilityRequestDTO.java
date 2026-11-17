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

    /**
     * QA round 29 sweep (D1318 class): a working day states its hours. Checked
     * with the other fields (it was refused after them, by the service).
     */
    @jakarta.validation.constraints.AssertTrue(message = "{availability.time.required}")
    @com.example.hms.validation.ReportedOn("availableFrom")
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isHoursGivenUnlessDayOff() {
        return Boolean.TRUE.equals(dayOff) || (availableFrom != null && availableTo != null);
    }
}
