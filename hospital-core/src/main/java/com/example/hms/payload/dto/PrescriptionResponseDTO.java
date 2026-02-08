package com.example.hms.payload.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
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
    private String notes;

    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
