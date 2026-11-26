package com.example.hms.payload.dto.pharmacy;

import java.math.BigDecimal;
import java.util.UUID;

/** An explicit reconciliation of the aggregate to the independently checked lot total. */
public record StockBalanceReconciliationRequest(BigDecimal expectedOnHand, BigDecimal expectedLotTotal,
    Long expectedItemRevision, Boolean confirmed, String reason, UUID witnessedBy,
    java.util.List<LotCount> lotCounts) {
    public record LotCount(UUID lotId, BigDecimal countedQuantity) { }
}
