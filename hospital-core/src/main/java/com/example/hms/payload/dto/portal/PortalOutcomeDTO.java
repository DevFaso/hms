package com.example.hms.payload.dto.portal;

import com.example.hms.enums.PatientReportedOutcomeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortalOutcomeDTO {

    private UUID id;
    private PatientReportedOutcomeType outcomeType;
    private String typeLabel;
    private Integer score;
    private String notes;
    private LocalDate reportDate;
    private UUID encounterId;
    private LocalDateTime createdAt;
}
