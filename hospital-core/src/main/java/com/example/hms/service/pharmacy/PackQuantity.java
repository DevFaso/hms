package com.example.hms.service.pharmacy;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.pharmacy.InventoryItem;

import java.math.BigDecimal;

/**
 * QA D2674 (r65, V311): stock received in packs ("plaquette de 10 comprimés") is counted in the unit prescriptions
 * are written in ("comprimé") once its pack size is set ({@code InventoryItem.unitsPerPack}). A quantity given in
 * whole packs is converted here, the one place, to the units the stock is counted in.
 */
public final class PackQuantity {

    private PackQuantity() {
    }

    /**
     * {@code quantity} in the item's unit: as given, or {@code quantity × unitsPerPack} when {@code inPacks}. A pack
     * quantity on an item with no pack size, or a fraction of a pack, is refused on {@code field}.
     */
    public static BigDecimal toUnits(InventoryItem item, BigDecimal quantity, Boolean inPacks, String field) {
        if (!Boolean.TRUE.equals(inPacks) || quantity == null) {
            return quantity;
        }
        Integer perPack = item != null ? item.getUnitsPerPack() : null;
        if (perPack == null || perPack < 2) {
            throw new FieldBusinessException(field, "pharmacy.pack.noPackSize");
        }
        if (!WholeUnitQuantity.isWhole(quantity)) {
            throw new FieldBusinessException(field, "pharmacy.pack.wholePacksOnly");
        }
        return quantity.multiply(BigDecimal.valueOf(perPack.longValue()));
    }
}
