package com.example.hms.payload.dto.pharmacy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record StockCountResponseDTO(UUID id, UUID inventoryItemId, UUID stockLotId, String medicationName,
    String lotNumber, String unit, BigDecimal bookQuantity, BigDecimal countedQuantity, BigDecimal variance,
    String reason, String recordedByName, LocalDateTime recordedAt, LocalDateTime appliedAt,
    String appliedByName, UUID adjustmentId) { }
