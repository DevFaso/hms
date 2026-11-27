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

    private Boolean active;
}
