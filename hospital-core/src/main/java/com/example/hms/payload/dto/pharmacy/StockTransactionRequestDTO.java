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
public class StockTransactionRequestDTO {

    @NotNull(message = "Inventory item ID is required")
    private UUID inventoryItemId;

    private UUID stockLotId;

    @NotNull(message = "Transaction type is required")
    private String transactionType;

    @NotNull(message = "Quantity is required")
    private BigDecimal quantity;

    @Size(max = 500)
    private String reason;

    private UUID referenceId;

    @NotNull(message = "Performed-by user ID is required")
    private UUID performedBy;
}
