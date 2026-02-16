package com.example.hms.payload.dto;

import com.example.hms.enums.EncounterType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EncounterTreatmentResponseDTO {

    private UUID id;

    private UUID encounterId;
    private String encounterCode;
    private EncounterType encounterType;

    private UUID patientId;
    private String patientFullName;
    private String patientPhoneNumber;

    private UUID treatmentId;
    private String treatmentName;

    private UUID staffId;
    private String staffFullName;

    private LocalDateTime performedAt;
    private String outcome;
    private String notes;

}
