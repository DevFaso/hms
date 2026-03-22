package com.example.hms.payload.dto.portal;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalDischargeInstructionsDTO {

    private UUID id;
    private UUID encounterId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dischargeDate;

    private String disposition;
    private String dischargeDiagnosis;
    private String hospitalCourse;
    private String dischargeCondition;

    private String activityRestrictions;
    private String dietInstructions;
    private String woundCareInstructions;
    private String followUpInstructions;
    private String warningSigns;
    private String patientEducationProvided;

    private List<String> equipmentAndSupplies;

    private Boolean isFinalized;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime finalizedAt;
}
