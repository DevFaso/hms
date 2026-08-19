package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InventoryItemRequestDTO {

    @NotNull(message = "Pharmacy ID is required")
    private UUID pharmacyId;

    @NotNull(message = "Medication catalog item ID is required")
    private UUID medicationCatalogItemId;

    private BigDecimal quantityOnHand;

    private BigDecimal reorderThreshold;

    private BigDecimal reorderQuantity;

    @Size(max = 60)
    private String unit;

    private Boolean active;
}
