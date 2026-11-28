package com.example.hms.payload.dto.pharmacy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Gross stock issues and returns/voids; never an estimate of doses administered to patients. */
public record PharmacyConsumptionDTO(LocalDate from, LocalDate to, List<StockConsumptionLine> items) {
    /**
     * One medicine in one recorded unit. {@code unit} is null, and {@code unitHistoryComparable} false, for the
     * movements recorded before the unit snapshot (V398): their quantities are given, their unit is not known.
     */
    public record StockConsumptionLine(UUID inventoryItemId, String medicationName, String strength, String form,
        String unit, BigDecimal issuedQuantity, BigDecimal returnedQuantity, boolean unitHistoryComparable,
        long sourceMovements) { }
}
