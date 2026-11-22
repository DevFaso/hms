package com.example.hms.service.pharmacy;

import com.example.hms.enums.ReturnedStockCondition;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * QA D3403 / D3415 (r70): the one rule for medicine that comes back to the pharmacy. A cancelled preparation went
 * back on sale whatever the reason (damaged stock included), and a morphine ampoule a patient returned went straight
 * back into the saleable lot.
 *
 * <ul>
 *   <li>Stock that is not intact (damaged, expired, contaminated) is never put back on sale.</li>
 *   <li>A controlled drug that comes back from a patient is never put back on sale, whatever its state: it left the
 *       pharmacy's custody.</li>
 * </ul>
 *
 * <p>Such units are written as the RETURN that brought them back followed by a QUARANTINE of the same quantity, and
 * the lot does not move: the ledger still adds up to the lot, the register shows both rows, and the quarantined units
 * wait for a witnessed destruction ({@link QuarantineDestructionService}).
 */
@Component
@RequiredArgsConstructor
public class StockQuarantine {

    /** The QUARANTINE row's reason, followed by the condition's name. */
    public static final String REASON_PREFIX = "Quarantined: ";

    private final StockTransactionRepository stockTransactionRepository;

    /**
     * True when units coming back in this state go to quarantine instead of back on sale.
     *
     * @param leftThePharmacy true for a return from a patient, a counter customer or a ward (the units left the
     *                        pharmacy's custody); false for a prepared fill that never left the counter
     */
    public static boolean quarantines(InventoryItem item, ReturnedStockCondition condition, boolean leftThePharmacy) {
        if (leftThePharmacy && ControlledStockWitness.isControlled(item)) {
            return true;
        }
        return condition != null && !condition.isFitForSale();
    }

    /**
     * Sets {@code quantity} units aside: the QUARANTINE row after the RETURN that brought them back. The caller has
     * NOT put them on the lot.
     */
    public StockTransaction quarantine(InventoryItem item, StockLot lot, BigDecimal quantity, UUID referenceId,
                                       ReturnedStockCondition condition, User performer, User witness) {
        // The units came back as they left: whole units of an item counted in units.
        WholeUnitQuantity.require(item, quantity);
        ReturnedStockCondition state = condition != null ? condition : ReturnedStockCondition.INTACT;
        return stockTransactionRepository.save(StockTransaction.builder()
                .inventoryItem(item)
                .stockLot(lot)
                .transactionType(StockTransactionType.QUARANTINE)
                .referenceId(referenceId)
                .quantity(quantity)
                .reason(REASON_PREFIX + state.name())
                .performedByUser(performer)
                .witnessedByUser(witness)
                .build());
    }
}
