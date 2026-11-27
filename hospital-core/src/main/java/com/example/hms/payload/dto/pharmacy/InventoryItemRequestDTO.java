package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InventoryItemRequestDTO {

    @NotNull(message = "{inventoryItem.pharmacyId.required}")
    private UUID pharmacyId;

    @NotNull(message = "{inventoryItem.medicationCatalogItemId.required}")
    private UUID medicationCatalogItemId;

    private BigDecimal quantityOnHand;

    private BigDecimal reorderThreshold;

    private BigDecimal reorderQuantity;

    @Size(max = 60)
    private String unit;

    /** QA D264: the selling price of one unit, in whole XOF. */
    @jakarta.validation.constraints.PositiveOrZero
    @jakarta.validation.constraints.Digits(integer = 10, fraction = 0, message = "{pharmacy.inventory.price.whole}")
    private BigDecimal unitPrice;

    /**
     * Review of #1239: why the selling price changes. Required when {@link #unitPrice} differs from the item's
     * current price; kept in the price history with who and when. Never card data.
     */
    @Size(max = 500)
    @com.example.hms.validation.NoCardNumber
    private String priceChangeReason;

    /**
     * QA D4181 (r81): true documents the item as handed over at no charge at this pharmacy, false withdraws that.
     * Null leaves it as it is. Like a price, set by the store manager or a hospital administrator, with
     * {@link #freeOfChargeReason} (true) or {@link #priceChangeReason} (false), and kept in the price history.
     */
    private Boolean freeOfCharge;

    /** QA D4181: why the item is free (a national programme, a donation). Required with {@code freeOfCharge=true}. */
    @Size(max = 500)
    @com.example.hms.validation.NoCardNumber
    private String freeOfChargeReason;

    private Boolean active;
}
