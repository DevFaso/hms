package com.example.hms.service.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.exception.RequiredFields;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.pharmacy.StockBalanceReconciliationRequest;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit, auditable repair of an aggregate that no longer equals its existing lot balances. */
@Service
@RequiredArgsConstructor
public class StockBalanceReconciliationService {
    private final InventoryItemRepository items;
    private final StockLotRepository lots;
    private final StockTransactionRepository ledger;
    private final UserRepository users;
    private final RoleValidator roles;
    private final ControlledStockWitness witness;
    private final EntityManager entityManager;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Transactional
    public UUID reconcile(UUID itemId, StockBalanceReconciliationRequest request) {
        UUID hospitalId = roles.requireActiveHospitalId();
        UUID userId = roles.getCurrentUserId();
        if (hospitalId == null || userId == null || !(roles.isSuperAdminActingAt(hospitalId)
            || roles.hasRole(userId, hospitalId, "PHARMACIST") || roles.hasRole(userId, hospitalId, "STORE_MANAGER")
            || roles.hasRole(userId, hospitalId, "HOSPITAL_ADMIN"))) {
            throw new com.example.hms.exception.ClientSafeAccessDeniedException(com.example.hms.utility.MessageUtil.resolve("stockCount.forbidden"));
        }
        RequiredFields.check().requireValue(request.expectedOnHand(), "expectedOnHand", "stockCount.startRequired")
            .requireValue(request.expectedLotQuantity(), "expectedLotQuantity", "stockCount.startRequired")
            .requireValue(request.expectedItemRevision(), "expectedItemRevision", "stockCount.startRequired")
            .requireValue(request.reason(), "reason", "stockCount.reasonRequired")
            .requireValue(request.lotCounts(), "lotCounts", "stockCount.lotCountEvidenceRequired")
            .require(Boolean.TRUE.equals(request.confirmed()), "confirmed", "stockCount.confirmRequired").orThrow();
        if (request.reason().trim().length() > 300) throw new FieldBusinessException("reason", "stockCount.reasonRequired");
        var item = items.findById(itemId).orElseThrow(() -> new ResourceNotFoundException("stockCount.notFound"));
        if (item.getPharmacy() == null || item.getPharmacy().getHospital() == null
            || !hospitalId.equals(item.getPharmacy().getHospital().getId())) throw new ResourceNotFoundException("stockCount.notFound");
        items.lockById(itemId).orElseThrow(() -> new ResourceNotFoundException("stockCount.notFound"));
        entityManager.refresh(item);
        var recorded = new java.util.HashMap<UUID, java.math.BigDecimal>();
        for (var count : request.lotCounts()) {
            if (count == null || count.lotId() == null || count.countedQuantity() == null
                || count.countedQuantity().signum() < 0 || recorded.putIfAbsent(count.lotId(), count.countedQuantity()) != null) {
                throw new FieldBusinessException("lotCounts", "stockCount.lotCountEvidenceRequired");
            }
        }
        var currentLots = lots.findAllByInventoryItemIdOrderByExpiryDateAsc(itemId).stream()
            .sorted(java.util.Comparator.comparing(com.example.hms.model.pharmacy.StockLot::getId)).toList();
        if (currentLots.isEmpty() || currentLots.size() != recorded.size())
            throw new FieldBusinessException("lotCounts", "stockCount.lotCountEvidenceRequired");
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        try {
            for (var candidate : currentLots) {
                var lot = lots.tryLockById(candidate.getId()).orElseThrow(() -> new ResourceNotFoundException("stockCount.notFound"));
                entityManager.refresh(lot);
                var physicallyCounted = recorded.get(lot.getId());
                if (physicallyCounted == null || physicallyCounted.compareTo(lot.getRemainingQuantity()) != 0)
                    throw new FieldBusinessException("lotCounts", "stockCount.lotCountEvidenceRequired");
                total = total.add(physicallyCounted);
            }
        } catch (org.springframework.dao.DataAccessException busy) {
            if (!StockRowLocks.isContention(busy)) throw busy;
            throw new FieldBusinessException("lotCounts", "stockCount.stockBusy");
        }
        if (!java.util.Objects.equals(item.getStockRevision(), request.expectedItemRevision())
            || item.getQuantityOnHand().compareTo(request.expectedOnHand()) != 0
            || total.compareTo(request.expectedLotQuantity()) != 0) throw new BusinessException("stockCount.changedSinceCount");
        var difference = total.subtract(item.getQuantityOnHand());
        if (difference.signum() == 0) throw new FieldBusinessException("confirmed", "stockCount.alreadyReconciled");
        WholeUnitQuantity.require(item, total);
        var actor = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("user.notFound"));
        var actualWitness = witness.resolve(request.witnessedBy(), item, actor);
        var movement = ledger.saveAndFlush(StockTransaction.builder().inventoryItem(item).transactionType(StockTransactionType.ADJUSTMENT)
            .quantity(difference).reason(com.example.hms.utility.MessageUtil.resolve("stockCount.balanceAdjustmentReason", request.reason().trim()))
            .performedByUser(actor).witnessedByUser(actualWitness).build());
        for (var entry : recorded.entrySet()) {
            jdbc.update("INSERT INTO clinical.stock_balance_reconciliation_lot_counts (movement_id, stock_lot_id, counted_quantity) VALUES (?, ?, ?)",
                movement.getId(), entry.getKey(), entry.getValue());
        }
        if (items.setOnHand(itemId, total, LocalDateTime.now()) != 1) throw new BusinessException("stockCount.changedSinceCount");
        return movement.getId();
    }
}
