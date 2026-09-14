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

    @NotNull(message = "{sessionTemplate.staffId.required}")
    private UUID staffId;

    @NotNull(message = "{sessionTemplate.departmentId.required}")
    private UUID departmentId;

    /** Null = a general session that accepts any visit type. */
    private UUID visitTypeId;

    /** ISO-8601: 1 = Monday .. 7 = Sunday. */
    @NotNull(message = "{sessionTemplate.dayOfWeek.required}")
    @Min(value = 1, message = "{sessionTemplate.dayOfWeek.invalid}")
    @Max(value = 7, message = "{sessionTemplate.dayOfWeek.invalid}")
    private Integer dayOfWeek;

    @NotNull(message = "{sessionTemplate.startTime.required}")
    private LocalTime startTime;

    @NotNull(message = "{sessionTemplate.endTime.required}")
    private LocalTime endTime;

    @NotNull(message = "{sessionTemplate.slotMinutes.required}")
    @Min(value = 1, message = "{sessionTemplate.slotMinutes.min}")
    private Integer slotMinutes;

    @NotNull(message = "{sessionTemplate.effectiveFrom.required}")
    private LocalDate effectiveFrom;

    /** Null = open-ended. */
    private LocalDate effectiveTo;

    @Size(max = 500)
    private String notes;
}
