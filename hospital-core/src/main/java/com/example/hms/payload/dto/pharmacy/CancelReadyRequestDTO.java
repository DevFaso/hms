package com.example.hms.payload.dto.pharmacy;

import com.example.hms.enums.ReadyCancelReason;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * G15: {@code POST /pharmacy/dispense/{id}/cancel-ready}. The reason is one of
 * STOCK_UNAVAILABLE, PATIENT_DECLINED, NOT_COLLECTED or OTHER; the
 * PRESCRIPTION_* values are the system's own and are refused.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@com.example.hms.validation.StockUnavailableNotIntact
public class CancelReadyRequestDTO {

    @NotNull(message = "{dispense.ready.cancelReason.required}")
    private ReadyCancelReason reason;

    /**
     * QA D3403 (r70): the state of the prepared medicine, said every time (no default). Only INTACT (not given,
     * intact) goes back on sale; damaged, expired or contaminated stock is quarantined.
     */
    @NotNull(message = "{dispense.ready.stockConditionRequired}")
    private com.example.hms.enums.ReturnedStockCondition stockCondition;

    /** Review of #1285: the witness of a controlled fill set aside or lost (required then, never the actor). */
    private java.util.UUID witnessedBy;
}
