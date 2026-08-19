package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DispenseResponseDTO {

    private UUID id;
    private UUID prescriptionId;
    private UUID patientId;
    private UUID pharmacyId;
    private UUID stockLotId;
    private UUID dispensedBy;
    private UUID verifiedBy;
    private UUID medicationCatalogItemId;
    private String medicationName;
    private BigDecimal quantityRequested;
    private BigDecimal quantityDispensed;
    private String unit;
    private boolean substitution;
    private String substitutionReason;
    private String status;
    private String notes;
    private LocalDateTime dispensedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
