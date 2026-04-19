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
public class InventoryItemResponseDTO {

    private UUID id;
    private UUID pharmacyId;
    private UUID medicationCatalogItemId;
    private String medicationName;
    private String medicationCode;
    private BigDecimal quantityOnHand;
    private BigDecimal reorderThreshold;
    private BigDecimal reorderQuantity;
    private String unit;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
