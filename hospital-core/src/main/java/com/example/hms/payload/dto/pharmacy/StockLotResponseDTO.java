package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StockLotResponseDTO {

    private UUID id;
    private UUID inventoryItemId;
    private String lotNumber;
    private LocalDate expiryDate;
    private BigDecimal initialQuantity;
    private BigDecimal remainingQuantity;
    private String supplier;
    private BigDecimal unitCost;
    private LocalDate receivedDate;
    private UUID receivedBy;
    private String notes;

    /**
     * Scannable identifier printed on the lot label (V138). Null on lots
     * received before the migration — printing a label backfills it.
     */
    private String barcodeValue;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
