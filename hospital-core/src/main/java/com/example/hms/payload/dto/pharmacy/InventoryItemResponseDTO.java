package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InventoryItemResponseDTO {

    private UUID id;
    private UUID pharmacyId;
    private UUID medicationCatalogItemId;
    private String medicationName;
    private String medicationCode;
    private BigDecimal quantityOnHand;
    private Long stockRevision;
    private BigDecimal reorderThreshold;
    private BigDecimal reorderQuantity;
    private BigDecimal unitPrice;
    /** QA D4181 (r81): the item is documented as handed over at no charge at this pharmacy. */
    private boolean freeOfCharge;
    /** QA D4181: why, as recorded by the store manager or the administrator. */
    private String freeOfChargeReason;
    private String unit;
    /** QA D2674 (r65): units of {@code unit} in one pack, when the item was converted; null otherwise. */
    private Integer unitsPerPack;
    /** QA D2674: the pack's name ("plaquette"). */
    private String packUnit;
    /** QA D2674: the stock on hand as whole packs (with {@link #looseUnitsOnHand}); null without a pack size. */
    private BigDecimal fullPacksOnHand;
    /** QA D2674: the units left over beyond {@link #fullPacksOnHand}. */
    private BigDecimal looseUnitsOnHand;
    private boolean active;
    /** QA D157: a controlled substance; a manual movement of it needs a witness. */
    private boolean controlled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
