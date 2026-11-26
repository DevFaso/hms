package com.example.hms.payload.dto.pharmacy;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** An explicit reconciliation of the aggregate to the independently checked lot total. */
public record StockBalanceReconciliationRequest(@NotNull @PositiveOrZero BigDecimal expectedOnHand,
    @NotNull @PositiveOrZero BigDecimal expectedLotQuantity,
    @NotNull Long expectedItemRevision, @NotNull Boolean confirmed, @NotBlank String reason, UUID witnessedBy,
    @NotNull @Valid java.util.List<@NotNull @Valid LotCount> lotCounts) {
    @AssertTrue
    @com.example.hms.validation.ReportedOn("confirmed")
    public boolean isConfirmationAccepted() { return Boolean.TRUE.equals(confirmed); }
    public record LotCount(@NotNull UUID lotId, @NotNull @PositiveOrZero BigDecimal countedQuantity) { }
}
