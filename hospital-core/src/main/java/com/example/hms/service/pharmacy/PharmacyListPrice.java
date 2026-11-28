package com.example.hms.service.pharmacy;

import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.repository.pharmacy.InventoryItemRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D264 / D2671 (r65): the one place a pharmacy's selling price is read. The price of one unit of a medication at
 * a pharmacy is the selling price set on that pharmacy's inventory item; empty when the item is missing or the
 * pharmacy has not priced it (zero counts as not priced). Every money path of the pharmacy (a dispense's charge, a
 * counter sale's line, a refund) prices from here and never from a figure the client sent
 * ({@code ClientMoneyFieldGuardTest}).
 */
public final class PharmacyListPrice {

    private PharmacyListPrice() {
    }

    /**
     * The selling price of one unit of {@code item}, when the pharmacy priced it. An item documented as free of
     * charge ({@link #isFree}) has no selling price, whatever figure it still carries.
     */
    public static Optional<BigDecimal> of(InventoryItem item) {
        if (item == null || isFree(item) || item.getUnitPrice() == null || item.getUnitPrice().signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(item.getUnitPrice());
    }

    /**
     * QA D4181 (r81): whether the pharmacy documented {@code item} as handed over at no charge. The only way an
     * item leaves the counter with nothing to pay and no price ({@link FillPriceGate}).
     */
    public static boolean isFree(InventoryItem item) {
        return item != null && item.getFreeOfChargeReason() != null && !item.getFreeOfChargeReason().isBlank();
    }

    /** The selling price of one unit of a catalog medication at a pharmacy, when priced there. */
    public static Optional<BigDecimal> of(UUID pharmacyId, UUID catalogItemId, InventoryItemRepository repository) {
        if (pharmacyId == null || catalogItemId == null) {
            return Optional.empty();
        }
        return repository.findByPharmacyIdAndMedicationCatalogItemId(pharmacyId, catalogItemId)
                .flatMap(PharmacyListPrice::of);
    }

    /** {@code quantity} units at {@code unitPrice}, in whole francs (the franc CFA has no minor unit). */
    public static BigDecimal amount(BigDecimal unitPrice, BigDecimal quantity) {
        return unitPrice.multiply(quantity).setScale(0, RoundingMode.HALF_UP);
    }
}
