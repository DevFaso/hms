package com.example.hms.payload.dto.nurse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Flowsheet entry response (MVP3).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowsheetEntryResponseDTO {

    private UUID id;
    private UUID patientId;
    private String patientName;
    private String type;
    private Double numericValue;
    private String unit;
    private String textValue;
    private String subType;
    private LocalDateTime recordedAt;
    private String recordedByName;
    private String notes;
}
