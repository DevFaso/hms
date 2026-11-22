package com.example.hms.payload.dto.pharmacy;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * QA D3415 (r70): {@code POST /pharmacy/stock-transactions/quarantine/destructions}. Destroys quarantined units of
 * an item (and lot); witnessed when the item is controlled. Required fields are named by the service, together.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuarantineDestructionRequestDTO {

    private UUID inventoryItemId;

    /** The lot the quarantined units came from; empty for units quarantined without a lot. */
    private UUID stockLotId;

    private BigDecimal quantity;

    /** How they were destroyed (incinerated, returned to the supplier, ...). */
    @Size(max = 300)
    @com.example.hms.validation.NoCardNumber
    private String reason;

    /** The colleague who saw the destruction of a controlled drug. */
    private UUID witnessedBy;
}
