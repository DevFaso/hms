package com.example.hms.payload.dto.portal;

import com.example.hms.enums.PatientReportedOutcomeType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortalOutcomeRequestDTO {

    @NotNull
    private PatientReportedOutcomeType outcomeType;

    @NotNull @Min(0) @Max(10)
    private Integer score;

    private String notes;

    @NotNull
    private LocalDate reportDate;

    private UUID encounterId;
}
