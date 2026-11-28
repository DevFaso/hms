package com.example.hms.service.pharmacy;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.repository.pharmacy.InventoryItemRepository;

/**
 * QA D4181 (r81): the one rule every fill passes before it is prepared, recorded or handed over. What leaves the
 * counter is either sold at the selling price of the dispensing pharmacy's own inventory item, or documented on
 * that item as free of charge. A fill with neither used to be charged nothing and left unpaid with no trace, and
 * every destination of a stock transfer was such an item. Refused with a sentence that says who sets the price;
 * the counter sale already refused the same item ({@code pharmacy.sale.noListPrice}).
 *
 * <p>Checked on the pharmacy's item, never on a figure the client sent ({@code ClientMoneyFieldGuardTest}).
 * {@code UnpricedFillGuardTest} keeps every writer of a fill behind this gate.
 */
public final class FillPriceGate {

    /** The refusal's message key: no selling price and no documented free-of-charge reason. */
    public static final String NO_PRICE_KEY = "pharmacy.dispense.noPrice";

    private FillPriceGate() {
    }

    /** What a fill costs at the pharmacy it leaves from. */
    public enum Standing {
        /** The pharmacy's item has a selling price: the fill has a charge. */
        PRICED,
        /** The pharmacy documented the item as free of charge: nothing is owed, for a recorded reason. */
        FREE,
        /** Neither: the fill cannot leave. */
        UNPRICED
    }

    /** The standing of {@code medication} at {@code pharmacy}; UNPRICED when the pharmacy does not hold it. */
    public static Standing standingOf(Pharmacy pharmacy, MedicationCatalogItem medication,
                                      InventoryItemRepository inventoryItemRepository) {
        if (pharmacy == null || pharmacy.getId() == null || medication == null || medication.getId() == null) {
            return Standing.UNPRICED;
        }
        InventoryItem item = inventoryItemRepository
                .findByPharmacyIdAndMedicationCatalogItemId(pharmacy.getId(), medication.getId()).orElse(null);
        if (PharmacyListPrice.isFree(item)) {
            return Standing.FREE;
        }
        return PharmacyListPrice.of(item).isPresent() ? Standing.PRICED : Standing.UNPRICED;
    }

    /** Refuses a fill of {@code medication} from {@code pharmacy} that is neither priced nor documented as free. */
    public static Standing require(Pharmacy pharmacy, MedicationCatalogItem medication, String medicationName,
                                   InventoryItemRepository inventoryItemRepository) {
        Standing standing = standingOf(pharmacy, medication, inventoryItemRepository);
        if (standing == Standing.UNPRICED) {
            throw new FieldBusinessException("pharmacyId", NO_PRICE_KEY,
                    medicationName == null || medicationName.isBlank() ? "—" : medicationName.trim(),
                    pharmacy == null || pharmacy.getName() == null ? "—" : pharmacy.getName());
        }
        return standing;
    }

    /** The same rule for a fill already prepared, read again when it is handed over. */
    public static Standing require(Dispense dispense, InventoryItemRepository inventoryItemRepository) {
        return require(dispense.getPharmacy(), dispense.getMedicationCatalogItem(), dispense.getMedicationName(),
                inventoryItemRepository);
    }
}
