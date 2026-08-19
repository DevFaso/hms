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
public class StockTransactionResponseDTO {

    private UUID id;
    private UUID inventoryItemId;
    private UUID stockLotId;
    private String transactionType;
    private BigDecimal quantity;
    private String reason;
    private UUID referenceId;
    private UUID performedBy;
    private LocalDateTime createdAt;

    // ── FU-2 (P-06 backend): first-class RECEIPT fields ──
    private String lotNumber;
    private String supplier;
    private String poReference;
    private LocalDate expiryDate;
    private BigDecimal unitCost;
}
