package com.example.hms.payload.dto.pharmacy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QA D967: one line of the controlled-drug register - a movement of a
 * controlled item's stock as the ledger recorded it, with the running balance
 * of that drug after it. Patient and prescriber are filled for a dispense and
 * for a return booked against one; the witness wherever one was recorded.
 */
public record ControlledRegisterEntryDTO(
        UUID transactionId,
        LocalDateTime occurredAt,
        String movementType,
        /** Signed: what came in is positive, what went out negative. */
        BigDecimal quantity,
        String lotNumber,
        BigDecimal balanceAfter,
        String patientName,
        String prescriberName,
        String recordedBy,
        String witnessedBy,
        String reason) {
}
