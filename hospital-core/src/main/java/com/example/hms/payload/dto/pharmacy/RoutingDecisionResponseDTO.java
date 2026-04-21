package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RoutingDecisionResponseDTO {

    private UUID id;
    private UUID prescriptionId;
    private String routingType;
    private UUID targetPharmacyId;
    private String targetPharmacyName;
    private UUID decidedByUserId;
    private UUID patientId;
    private String reason;
    private LocalDate estimatedRestockDate;
    private String status;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
