package com.example.hms.payload.dto.pharmacy;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record StockCountRequestDTO(
    @NotNull(message = "{stockTransaction.inventoryItemId.required}") UUID inventoryItemId,
    UUID stockLotId,
    @NotNull(message = "{stockCount.requestRequired}") UUID clientRequestId,
    @NotNull(message = "{stockCount.startRequired}") BigDecimal expectedBookQuantity,
    @Size(max = 60) String expectedUnit,
    @NotNull(message = "{stockCount.quantityRequired}")
    @DecimalMin(value = "0", message = "{stockCount.quantityNonNegative}")
    @Digits(integer = 10, fraction = 2, message = "{stockCount.quantityPrecision}") BigDecimal countedQuantity,
    @NotBlank(message = "{stockCount.reasonRequired}")
    @Size(max = 300, message = "{stockCount.reasonRequired}") String reason) { }
