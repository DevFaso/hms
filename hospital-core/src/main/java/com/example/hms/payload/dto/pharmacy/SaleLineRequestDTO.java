package com.example.hms.payload.dto.pharmacy;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
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
public class SaleLineRequestDTO {

    @NotNull(message = "{saleLine.medicationCatalogItemId.required}")
    private UUID medicationCatalogItemId;

    /**
     * The lot the line is sold from. QA D2670 (r65): required when the pharmacy keeps this item in lots, as a
     * dispense; the lot goes down and a SALE stock movement is written in the same transaction.
     */
    private UUID stockLotId;

    @NotNull(message = "{saleLine.quantity.required}")
    @DecimalMin(value = "0.001", message = "{saleLine.quantity.positive}")
    private BigDecimal quantity;

    /**
     * QA D2671 (r65): never trusted. The server prices every line from the pharmacy's list price (the inventory
     * item's selling price); a price sent here is accepted only when it is that price, and refused otherwise.
     * Optional: a client that leaves it out gets the list price.
     */
    @DecimalMin(value = "0.0", message = "{saleLine.unitPrice.min}")
    private BigDecimal unitPrice;

    /**
     * QA D2674 (r65): true when {@code quantity} counts whole packs of an item kept in units (its
     * {@code unitsPerPack}); the server converts to units. False or absent: {@code quantity} is in the item's unit.
     */
    private Boolean inPacks;

    @Size(max = 500)
    private String notes;

    /**
     * QA D964: why the chosen lot was taken although a shorter-dated usable
     * lot of the same item could serve this movement ({@code FefoLotPolicy}).
     * Required only then; recorded with the movement.
     */
    @Size(max = 300)
    private String fefoOverrideReason;
}
