package com.example.hms.service.pharmacy;

import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.StockTransaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Review of #1239: what a patient return gives back in money, computed in one place for the refund itself and for
 * the reconciliation line of a return nobody refunded.
 *
 * <ul>
 *   <li><b>A counter sale</b>: the returned quantity at the price of its sale line.</li>
 *   <li><b>A dispense</b>: the returned share of what the patient PAID for it (paid ÷ quantity dispensed × quantity
 *       returned). It was the returned quantity at today's list price: a price raised since the fill gave back more
 *       than was paid for those units, and a patient who paid only a co-payment got the full price back.</li>
 *   <li><b>Pack conversion</b>: a sale or a fill recorded before the item's pack size was set counts packs, a return
 *       recorded after it counts units. That return is converted to packs first (units ÷ units per pack); 2 tablets
 *       of a 2-box fill were refunded as 2 boxes.</li>
 * </ul>
 * Whole francs, half up. The caller still bounds the amount by what was paid less the other live refunds.
 */
final class PharmacyRefundAmount {

    private PharmacyRefundAmount() {
    }

    /**
     * The returned quantity, counted as the sale or the fill it gives back was counted: as recorded, or divided by
     * the pack size when the source predates the item's pack conversion and the return does not.
     */
    static BigDecimal inSourceUnits(StockTransaction ret, LocalDateTime sourceRecordedAt) {
        BigDecimal quantity = ret.getQuantity() != null ? ret.getQuantity() : BigDecimal.ZERO;
        InventoryItem item = ret.getInventoryItem();
        LocalDateTime setAt = item != null ? item.getPackSizeSetAt() : null;
        Integer perPack = item != null ? item.getUnitsPerPack() : null;
        if (setAt == null || perPack == null || perPack < 2 || sourceRecordedAt == null
                || ret.getCreatedAt() == null) {
            return quantity;
        }
        boolean sourceCountsPacks = sourceRecordedAt.isBefore(setAt);
        boolean returnCountsUnits = !ret.getCreatedAt().isBefore(setAt);
        if (sourceCountsPacks && returnCountsUnits) {
            return quantity.divide(BigDecimal.valueOf(perPack.longValue()), 6, RoundingMode.HALF_UP);
        }
        return quantity;
    }

    /** A return of a counter sale: the returned quantity at the sale line's price. */
    static BigDecimal ofSaleLine(BigDecimal lineUnitPrice, StockTransaction ret, LocalDateTime saleRecordedAt) {
        if (lineUnitPrice == null || lineUnitPrice.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return PharmacyListPrice.amount(lineUnitPrice, inSourceUnits(ret, saleRecordedAt));
    }

    /** A return of a dispense: the returned share of what the patient paid for it. */
    static BigDecimal ofDispense(BigDecimal paid, BigDecimal quantityDispensed, StockTransaction ret,
                                 LocalDateTime dispenseRecordedAt) {
        if (paid == null || paid.signum() <= 0 || quantityDispensed == null || quantityDispensed.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return paid.multiply(inSourceUnits(ret, dispenseRecordedAt))
                .divide(quantityDispensed, 0, RoundingMode.HALF_UP);
    }
}
