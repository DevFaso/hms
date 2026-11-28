package com.example.hms.service.pharmacy;

import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import org.jspecify.annotations.Nullable;

/**
 * QA D4184 (r81): the one place a stock row (a lot, a movement, a count) is given its medicine. Lot numbers repeat
 * across medicines (the same number on a 250 mg and a 500 mg lot), so a list keyed by lot that does not name the
 * product cannot be read. Every stock read model takes its product name, strength and form from here;
 * {@code StockRowNamesItsProductGuardTest} fails on a lot or movement DTO built without it.
 */
public final class StockProductLabel {

    private StockProductLabel() {
    }

    /** The product in the reader's language, with its strength when the name does not already state one. */
    @Nullable
    public static String nameOf(@Nullable MedicationCatalogItem medicine) {
        return medicine == null ? null : DispenseVerificationService.describeProduct(medicine);
    }

    @Nullable
    public static String nameOf(@Nullable InventoryItem item) {
        return item == null ? null : nameOf(item.getMedicationCatalogItem());
    }

    /** The strength as written ("500 mg"), when the catalogue states one. */
    @Nullable
    public static String strengthOf(@Nullable MedicationCatalogItem medicine) {
        return medicine == null ? null : ProductStrength.labelOf(medicine).orElse(null);
    }

    /** The dosage form as the catalogue records it (a code the reader's screen labels). */
    @Nullable
    public static String formOf(@Nullable MedicationCatalogItem medicine) {
        return medicine == null || medicine.getForm() == null || medicine.getForm().isBlank() ? null : medicine.getForm().trim();
    }
}
