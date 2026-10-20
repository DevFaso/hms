package com.example.hms.service.pharmacy;

import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.repository.pharmacy.InventoryItemRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * QA D264: what a dispense costs. The price per unit is the one set on the
 * dispensing pharmacy's inventory item for the medication; the charge is the
 * quantity dispensed times that price, in whole francs (the franc CFA has no
 * minor unit). Empty when the dispense names no catalog medication or the
 * pharmacy has not priced it, in which case the checkout cannot take money
 * for it.
 */
public final class DispenseCharge {

    private DispenseCharge() {
    }

    public static Optional<BigDecimal> of(Dispense dispense, InventoryItemRepository inventoryItemRepository) {
        if (dispense == null || dispense.getPharmacy() == null || dispense.getMedicationCatalogItem() == null
                || dispense.getQuantityDispensed() == null) {
            return Optional.empty();
        }
        return inventoryItemRepository
                .findByPharmacyIdAndMedicationCatalogItemId(dispense.getPharmacy().getId(),
                        dispense.getMedicationCatalogItem().getId())
                .map(InventoryItem::getUnitPrice)
                .filter(price -> price.signum() > 0)
                .map(price -> price.multiply(dispense.getQuantityDispensed()).setScale(0, RoundingMode.HALF_UP));
    }
}
