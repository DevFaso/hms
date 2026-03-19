package com.example.hms.payload.dto;

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
public class LabSpecimenResponseDTO {

    private UUID id;
    private UUID labOrderId;
    private String accessionNumber;
    private String barcodeValue;
    private String specimenType;
    private LocalDateTime collectedAt;
    private UUID collectedById;
    private LocalDateTime receivedAt;
    private UUID receivedById;
    private String currentLocation;
    private String status;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
