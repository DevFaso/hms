package com.example.hms.payload.dto.pharmacy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The stock observation's reason and recorder remain on every read response. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class StockCountResponseDTO {
    private UUID id;
    private UUID inventoryItemId;
    private UUID stockLotId;
    private String medicationName;
    private String lotNumber;
    private String unit;
    private BigDecimal bookQuantity;
    private BigDecimal countedQuantity;
    private BigDecimal variance;
    private String reason;
    private String recordedByName;
    private LocalDateTime recordedAt;
    private LocalDateTime appliedAt;
    private String appliedByName;
    private UUID adjustmentId;
    private Boolean samePersonConfirmation;
}
