package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.StockTransactionType;
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
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
// Review of #1285 (QA D1300): a RETURN names its stock state at the edge, with the other missing fields.
@com.example.hms.validation.ReturnStockStateRequired
public class StockTransactionRequestDTO {

    @NotNull(message = "{stockTransaction.inventoryItemId.required}")
    private UUID inventoryItemId;

    private UUID stockLotId;

    @NotNull(message = "{stockTransaction.transactionType.required}")
    private StockTransactionType transactionType;

    @NotNull(message = "{stockTransaction.quantity.required}")
    private BigDecimal quantity;

    @Size(max = 500)
    private String reason;

    /**
     * QA D964: why the chosen lot was taken although a shorter-dated usable
     * lot of the same item could serve this movement ({@code FefoLotPolicy}).
     * Required only then; recorded with the movement.
     */
    @Size(max = 300)
    private String fefoOverrideReason;

    private UUID referenceId;

    private UUID performedBy;

    /** QA D157: the colleague who witnessed a controlled-substance movement. */
    private UUID witnessedBy;

    /**
     * QA D3415 (r70): the state of a RETURN's units. Only INTACT goes back on sale; damaged, expired or contaminated
     * units are quarantined, and a controlled drug that comes back is quarantined whatever its state. Left out on a
     * RETURN: INTACT (the portal always says it).
     */
    private com.example.hms.enums.ReturnedStockCondition stockCondition;

    // ── FU-2 (P-06 backend): first-class RECEIPT fields, optional for other types ──

    @Size(max = 120)
    private String lotNumber;

    @Size(max = 200)
    private String supplier;

    @Size(max = 120)
    private String poReference;

    private LocalDate expiryDate;

    @DecimalMin(value = "0.0", message = "{stockTransaction.unitCost.min}")
    private BigDecimal unitCost;
}
