package com.example.hms.payload.dto.pharmacy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * QA D4183: a stock item whose total on hand no longer equals the sum of its lots, with what the balance
 * reconciliation needs to repair it (the item's revision, each lot and its balance).
 */
public record StockBalanceDriftDTO(UUID inventoryItemId, String medicationName, String strength, String form,
    String unit, boolean controlled, BigDecimal quantityOnHand, BigDecimal lotQuantity, BigDecimal difference,
    Long stockRevision, List<LotBalance> lots) {
    public record LotBalance(UUID lotId, String lotNumber, LocalDate expiryDate, BigDecimal remainingQuantity) { }
}
