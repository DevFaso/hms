package com.example.hms.payload.dto.scheduling;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionTemplateResponseDTO {

    private UUID id;
    private UUID staffId;
    private String staffName;
    private UUID departmentId;
    private String departmentName;
    private UUID visitTypeId;
    private String visitTypeName;
    /** ISO-8601: 1 = Monday .. 7 = Sunday. */
    private int dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
    private int slotMinutes;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private boolean active;
    private String notes;
}
