package com.example.hms.payload.dto.pharmacy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * QA D967: the controlled-drug (stupéfiants) register of one pharmacy over a
 * date range, read from the stock ledger - no clinical data of its own.
 * {@code truncated} is true when the period held more movements than one read
 * returns; the page then asks for a shorter range.
 */
public record ControlledRegisterDTO(
        UUID pharmacyId,
        String pharmacyName,
        LocalDate from,
        LocalDate to,
        boolean truncated,
        List<ControlledRegisterDrugDTO> drugs) {
}
