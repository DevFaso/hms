package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StockTransactionRequestDTO {

    @NotNull(message = "Inventory item ID is required")
    private UUID inventoryItemId;

    private UUID stockLotId;

    @NotNull(message = "Transaction type is required")
    private StockTransactionType transactionType;

    @NotNull(message = "Quantity is required")
    private BigDecimal quantity;

    @Size(max = 500)
    private String reason;

    private UUID referenceId;

    private UUID performedBy;

    // ── FU-2 (P-06 backend): first-class RECEIPT fields, optional for other types ──

    @Size(max = 120)
    private String lotNumber;

    @Size(max = 200)
    private String supplier;

    @Size(max = 120)
    private String poReference;

    private LocalDate expiryDate;

    @DecimalMin(value = "0.0", message = "Unit cost cannot be negative")
    private BigDecimal unitCost;
}
