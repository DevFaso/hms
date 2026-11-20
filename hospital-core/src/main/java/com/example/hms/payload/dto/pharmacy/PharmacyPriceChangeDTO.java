package com.example.hms.payload.dto.pharmacy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Review of #1239 (V311): one line of an inventory item's price history, as read back: the price before and after,
 * why it changed, who changed it and when. Prices are in whole francs CFA per unit of the item.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PharmacyPriceChangeDTO {

    private UUID id;
    private UUID inventoryItemId;
    /** Null when the item had no price before. */
    private BigDecimal oldPrice;
    /** Null when the price was removed. */
    private BigDecimal newPrice;
    private String reason;
    private UUID changedBy;
    private String changedByName;
    private LocalDateTime changedAt;
}
