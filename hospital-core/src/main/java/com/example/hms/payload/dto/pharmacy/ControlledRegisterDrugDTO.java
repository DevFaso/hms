package com.example.hms.payload.dto.pharmacy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * QA D967: one controlled drug's page of the register over the period: the
 * balance the ledger carried in, every movement with its running balance, the
 * balance carried out, and what the shelf holds now (a difference between the
 * two is for the inspector to see, not for the register to hide).
 */
public record ControlledRegisterDrugDTO(
        UUID inventoryItemId,
        String medicationName,
        String unit,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        BigDecimal quantityOnHand,
        List<ControlledRegisterEntryDTO> entries) {
}
