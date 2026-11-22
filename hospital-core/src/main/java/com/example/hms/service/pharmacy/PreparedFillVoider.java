package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.ReadyCancelReason;
import com.example.hms.enums.ReturnedStockCondition;
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
import java.util.UUID;

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

    /** QA D3403 (r70): the RETURN row of a cancelled fill that is NOT put back on sale. */
    public static final String NOT_FOR_SALE_PREFIX = "Prepared fill cancelled, not for sale";

    private final DispenseRepository dispenseRepository;
    private final StockLotRepository stockLotRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final StockTransactionRepository stockTransactionRepository;
    private final PharmacyServiceSupport support;
    private final EntityManager entityManager;
    private final Clock clock;
    private final PharmacyPaymentVoider paymentVoider;
    private final StockQuarantine stockQuarantine;
    /** Review of #1285: a controlled fill that does not go back on sale is witnessed, as every controlled movement. */
    private final ControlledStockWitness controlledStockWitness;

    /**
     * Review of #1284: the « à facturer » gate, whose open covers a cancelled fill withdraws. Optional: a sliced test
     * context may build this class without it.
     */
    private DispenseBillingDeferralService billingDeferrals;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBillingDeferrals(DispenseBillingDeferralService billingDeferrals) {
        this.billingDeferrals = billingDeferrals;
    }

    /**
     * The prescriber withdrew or changed the order: void its open
     * preparation, if it has one. The caller holds the prescription lock.
     *
     * @param reason {@link ReadyCancelReason#PRESCRIPTION_WITHDRAWN} or
     *               {@link ReadyCancelReason#PRESCRIPTION_CHANGED}
     */
    public void voidPreparedFill(Prescription prescription, ReadyCancelReason reason) {
        // The bag never left the pharmacy and nobody reported it damaged: it goes back on sale.
        dispenseRepository.findFirstByPrescription_IdAndStatus(prescription.getId(), DispenseStatus.PENDING)
                .ifPresent(open -> cancel(open, reason, ReturnedStockCondition.INTACT, null));
    }

    /**
     * Cancel one PENDING row. Returns the row as it now is in the database.
     *
     * @param condition QA D3403 (r70): the state of the prepared medicine; only INTACT goes back on sale
     * @param witnessId review of #1285: the colleague who saw a controlled fill set aside or lost (not the actor)
     * @throws ConflictException when the row is no longer PENDING
     */
    Dispense cancel(Dispense dispense, ReadyCancelReason reason, ReturnedStockCondition condition, UUID witnessId) {
        User witness = witnessOf(dispense, condition, witnessId);
        int changed = dispenseRepository.cancelPreparedFill(dispense.getId(), reason, LocalDateTime.now(clock));
        // The bulk UPDATE bypassed the managed copy; nothing touches it before
        // this resync, or a flush would write its stale PENDING back.
        Dispense current = resync(dispense);
        if (changed == 0) {
            throw new ConflictException(MessageUtil.resolve("dispense.ready.notPending"));
        }

        returnStock(current, condition, witness);
        // QA D404: a preparation paid at the till is voided with it.
        paymentVoider.voidForCancelledDispense(current, "Prepared fill cancelled: " + reason.name());
        // Review of #1284: a third-party cover was approved on this fill's charge: it goes with it.
        if (billingDeferrals != null && current.getPharmacy() != null && current.getPharmacy().getHospital() != null) {
            billingDeferrals.withdrawCoversOfCancelledFill(current.getPrescription(),
                    current.getPharmacy().getHospital().getId());
        }

        String prescriptionId = current.getPrescription() != null
                ? String.valueOf(current.getPrescription().getId()) : "unknown";
        support.logAudit(AuditEventType.DISPENSE_READY_CANCELLED,
                "Prepared fill cancelled, prescription " + prescriptionId + ", reason " + reason.name()
                        + ", stock " + condition.name(),
                current.getId().toString(), "DISPENSE");
        support.notifyReadyCancelled(current.getPrescription(), current.getPatient(), current.getPharmacy(), current.getMedicationName());
        log.info("Prepared fill {} cancelled ({})", current.getId(), reason);
        return current;
    }

    /**
     * The bag goes back on the shelf when it is intact. QA D3403 (r70): every reason put the full quantity back into
     * the saleable lot, damaged stock included. Stock that is damaged, expired or contaminated is now written as its
     * RETURN followed by a QUARANTINE, the lot unchanged ({@link StockQuarantine}), and waits for its destruction.
     */
    private void returnStock(Dispense dispense, ReturnedStockCondition condition, User witness) {
        StockLot lot = dispense.getStockLot();
        if (lot == null) {
            return;
        }
        InventoryItem item = lot.getInventoryItem();
        if (condition.isLoss()) {
            User performer = support.resolveCurrentUser();
            stockTransactionRepository.save(StockTransaction.builder()
                    .inventoryItem(item)
                    .stockLot(lot)
                    .transactionType(StockTransactionType.RETURN)
                    .referenceId(dispense.getId())
                    .quantity(dispense.getQuantityDispensed())
                    .reason(NOT_FOR_SALE_PREFIX + ": " + condition.name())
                    .performedByUser(performer)
                    .build());
            stockQuarantine.recordLoss(item, lot, dispense.getQuantityDispensed(), dispense.getId(), performer,
                    witness);
            return;
        }
        if (StockQuarantine.quarantines(item, condition, false)) {
            User performer = support.resolveCurrentUser();
            stockTransactionRepository.save(StockTransaction.builder()
                    .inventoryItem(item)
                    .stockLot(lot)
                    .transactionType(StockTransactionType.RETURN)
                    .referenceId(dispense.getId())
                    .quantity(dispense.getQuantityDispensed())
                    // The fill (and through it the prescription) is the row's reference.
                    .reason(NOT_FOR_SALE_PREFIX + ": " + condition.name())
                    .performedByUser(performer)
                    .build());
            stockQuarantine.quarantine(item, lot, dispense.getQuantityDispensed(), dispense.getId(), condition,
                    performer, witness);
            return;
        }
        // Atomic in the database (#825 security finding 1, round 5): every
        // stock writer moves quantities this way, so a return racing a fill,
        // a receipt or an adjustment of the same lot cannot be lost.
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
                .referenceId(dispense.getId())
                .quantity(dispense.getQuantityDispensed())
                .reason("Prepared fill cancelled — stock returned for prescription "
                        + (dispense.getPrescription() != null ? dispense.getPrescription().getId() : "unknown"))
                .performedByUser(performer)
                .build());
    }

    /**
     * Review of #1285: the witness of a controlled fill that does not go back on sale (quarantined or lost), checked
     * before anything moves: required, not the actor, a stock-witness role at the hospital. Null otherwise.
     */
    private User witnessOf(Dispense dispense, ReturnedStockCondition condition, UUID witnessId) {
        StockLot lot = dispense.getStockLot();
        if (condition.isFitForSale() || lot == null || lot.getInventoryItem() == null) {
            return null;
        }
        return controlledStockWitness.resolve(witnessId, lot.getInventoryItem(), support.resolveCurrentUser());
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
