package com.example.hms.payload.dto.pharmacy;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * QA D3415 (r70): units of one item (and lot) set aside, not for sale, waiting to be destroyed: everything
 * quarantined less everything destroyed.
 */
public record QuarantineLineDTO(
        UUID inventoryItemId,
        String medicationName,
        String unit,
        boolean controlled,
        UUID stockLotId,
        String lotNumber,
        BigDecimal heldQuantity) {
}
