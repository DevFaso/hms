package com.example.hms.payload.dto.nurse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Request to record a flowsheet entry (MVP3).
 */
@Data
public class FlowsheetEntryCreateRequestDTO {

    @NotNull
    private UUID patientId;

    /** INTAKE, OUTPUT, PAIN_ASSESSMENT, NEURO_CHECK, WOUND_ASSESSMENT, BLOOD_GLUCOSE, etc. */
    @NotBlank
    private String type;

    private Double numericValue;
    private String unit;
    private String textValue;
    private String subType;
    private LocalDateTime recordedAt;
    private String notes;
}
