package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.ReadyCancelReason;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.ConflictException;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.utility.MessageUtil;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * The one implementation of "a prepared fill is no longer waiting" (G15
 * rule 8): the conditional PENDING to CANCELLED update, the stock RETURN,
 * the audit and the after-commit SMS.
 *
 * <p>Two callers. The pharmacist's {@code cancel-ready}
 * ({@code DispenseServiceImpl.cancelReady}), which has already checked the
 * hospital scope and taken the prescription lock. And the prescriber's
 * withdrawal or edit ({@code PrescriptionServiceImpl.updatePrescription}),
 * whose transaction is already authorised and may belong to a super-admin in
 * global view: so this class makes <b>no</b> hospital-scope call of its own.
 * Modelled on {@code partner.WithdrawnOrderPartnerHandler}.
 *
 * <p>Both callers hold the prescription row lock (rule 1), so the update
 * cannot meet a hand-over halfway: lock order is prescription, then dispense.
 * Same transaction as the caller, so a failure here rolls the withdrawal or
 * the edit back.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PreparedFillVoider {

    private final DispenseRepository dispenseRepository;
    private final StockLotRepository stockLotRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final StockTransactionRepository stockTransactionRepository;
    private final PharmacyServiceSupport support;
    private final EntityManager entityManager;
    private final Clock clock;

    /**
     * The prescriber withdrew or changed the order: void its open
     * preparation, if it has one. The caller holds the prescription lock.
     *
     * @param reason {@link ReadyCancelReason#PRESCRIPTION_WITHDRAWN} or
     *               {@link ReadyCancelReason#PRESCRIPTION_CHANGED}
     */
    public void voidPreparedFill(Prescription prescription, ReadyCancelReason reason) {
        dispenseRepository.findFirstByPrescription_IdAndStatus(prescription.getId(), DispenseStatus.PENDING)
                .ifPresent(open -> cancel(open, reason));
    }

    /**
     * Cancel one PENDING row. Returns the row as it now is in the database.
     *
     * @throws ConflictException when the row is no longer PENDING
     */
    Dispense cancel(Dispense dispense, ReadyCancelReason reason) {
        int changed = dispenseRepository.cancelPreparedFill(dispense.getId(), reason, LocalDateTime.now(clock));
        // The bulk UPDATE bypassed the managed copy; nothing touches it before
        // this resync, or a flush would write its stale PENDING back.
        Dispense current = resync(dispense);
        if (changed == 0) {
            throw new ConflictException(MessageUtil.resolve("dispense.ready.notPending"));
        }

        returnStock(current);

        String prescriptionId = current.getPrescription() != null
                ? String.valueOf(current.getPrescription().getId()) : "unknown";
        support.logAudit(AuditEventType.DISPENSE_READY_CANCELLED,
                "Prepared fill cancelled, prescription " + prescriptionId + ", reason " + reason.name(),
                current.getId().toString(), "DISPENSE");
        support.notifyReadyCancelled(current.getPatient(), current.getPharmacy(), current.getMedicationName());
        log.info("Prepared fill {} cancelled ({})", current.getId(), reason);
        return current;
    }

    /**
     * The bag goes back on the shelf, STOCK_UNAVAILABLE included: a damaged
     * or missing pack is recorded through the stock-adjustment page
     * (ADJUSTMENT), as for any other shelf loss.
     */
    private void returnStock(Dispense dispense) {
        StockLot lot = dispense.getStockLot();
        if (lot == null) {
            return;
        }
        InventoryItem item = lot.getInventoryItem();
        // Atomic in the database (#825 security finding 1): a return racing a
        // fill of another order from the same lot cannot be lost.
        LocalDateTime now = LocalDateTime.now(clock);
        stockLotRepository.incrementRemaining(lot.getId(), dispense.getQuantityDispensed(), now);
        inventoryItemRepository.incrementOnHand(item.getId(), dispense.getQuantityDispensed(), now);
        if (entityManager.contains(lot)) {
            entityManager.refresh(lot);
        }
        if (entityManager.contains(item)) {
            entityManager.refresh(item);
        }

        User performer = support.resolveCurrentUser();
        stockTransactionRepository.save(StockTransaction.builder()
                .inventoryItem(item)
                .stockLot(lot)
                .transactionType(StockTransactionType.RETURN)
                .quantity(dispense.getQuantityDispensed())
                .reason("Prepared fill cancelled — stock returned for prescription "
                        + (dispense.getPrescription() != null ? dispense.getPrescription().getId() : "unknown"))
                .performedByUser(performer)
                .build());
    }

    /**
     * The row as the bulk update left it: a refresh of the managed copy, or
     * a fresh read when it is not managed. Only this row; the persistence
     * context is not cleared (the caller still uses its other entities).
     */
    private Dispense resync(Dispense dispense) {
        if (entityManager.contains(dispense)) {
            entityManager.refresh(dispense);
            return dispense;
        }
        return dispenseRepository.findById(dispense.getId()).orElse(dispense);
    }
}
