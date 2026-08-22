package com.example.hms.payload.dto.scheduling;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SessionTemplateRequestDTO {

    @NotNull(message = "A clinician is required.")
    private UUID staffId;

    @NotNull(message = "A department is required.")
    private UUID departmentId;

    /** Null = a general session that accepts any visit type. */
    private UUID visitTypeId;

    /** ISO-8601: 1 = Monday .. 7 = Sunday. */
    @NotNull(message = "A weekday is required.")
    @Min(value = 1, message = "Weekday must be 1 (Monday) to 7 (Sunday).")
    @Max(value = 7, message = "Weekday must be 1 (Monday) to 7 (Sunday).")
    private Integer dayOfWeek;

    @NotNull(message = "A start time is required.")
    private LocalTime startTime;

    @NotNull(message = "An end time is required.")
    private LocalTime endTime;

    @NotNull(message = "A slot length is required.")
    @Min(value = 1, message = "Slots must be at least one minute long.")
    private Integer slotMinutes;

    @NotNull(message = "An effective-from date is required.")
    private LocalDate effectiveFrom;

    /** Null = open-ended. */
    private LocalDate effectiveTo;

    @Size(max = 500)
    private String notes;
}
