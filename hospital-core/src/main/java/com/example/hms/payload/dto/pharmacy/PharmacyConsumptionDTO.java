package com.example.hms.payload.dto.pharmacy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Gross stock issues and returns/voids; never an estimate of doses administered to patients. */
public record PharmacyConsumptionDTO(LocalDate from, LocalDate to, List<Line> items) {
    public record Line(UUID inventoryItemId, String medicationName, String unit,
        BigDecimal issuedQuantity, BigDecimal returnedQuantity, boolean unitHistoryComparable, long sourceMovements) { }
}
