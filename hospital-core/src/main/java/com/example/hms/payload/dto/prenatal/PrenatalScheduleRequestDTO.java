package com.example.hms.payload.dto.prenatal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrenatalScheduleRequestDTO {

    @NotNull
    private UUID patientId;

    @NotNull
    private UUID hospitalId;

    private UUID staffId;

    @NotNull
    @PastOrPresent(message = "Last menstrual period cannot be set in the future")
    private LocalDate lastMenstrualPeriodDate;

    private LocalDate estimatedDueDate;

    private Boolean highRisk;

    /** Optional additional check-ins expressed as gestational week numbers. */
    @Size(max = 8)
    private List<Integer> supplementalVisitWeeks;

    private String notes;
}
