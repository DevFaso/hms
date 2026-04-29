package com.example.hms.payload.dto;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PrescriptionResponseDTO {

    private UUID id;

    private UUID patientId;
    private String patientFullName;
    private String patientEmail;

    private UUID staffId;
    private String staffFullName;

    private UUID encounterId;
    private UUID hospitalId;

    private String medicationName;
    private String medicationDisplayName;

    private String dosage;
    private String frequency;
    private String duration;
    private String route;
    private String instructions;
    private String notes;

    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * CDS rule-engine cards produced when the prescription was last
     * created or updated. Empty when the engine had nothing to flag.
     * Null on read-only responses where the engine did not run.
     */
    private List<CdsCard> cdsAdvisories;
}
