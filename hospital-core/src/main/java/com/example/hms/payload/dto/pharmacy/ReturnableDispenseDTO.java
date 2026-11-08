package com.example.hms.payload.dto.pharmacy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D932: a fill that stock can be returned against - completed or partial,
 * taken from the chosen lot (or, for an item not kept in lots, from the
 * item's pharmacy), with something left that has not come back yet. The
 * return form books the return against {@code dispenseId}.
 */
public record ReturnableDispenseDTO(
        UUID dispenseId,
        UUID prescriptionId,
        String patientName,
        String medicationName,
        LocalDateTime dispensedAt,
        BigDecimal quantityDispensed,
        BigDecimal returnableQuantity,
        String unit,
        String lotNumber) {
}
