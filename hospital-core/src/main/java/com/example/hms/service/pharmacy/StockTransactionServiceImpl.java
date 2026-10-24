package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.StockTransactionMapper;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockTransactionRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockTransactionResponseDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockTransactionServiceImpl implements StockTransactionService {

    private final StockTransactionRepository stockTransactionRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final StockLotRepository stockLotRepository;
    private final com.example.hms.repository.pharmacy.DispenseRepository dispenseRepository;
    private final PharmacyRepository pharmacyRepository;
    private final UserRepository userRepository;
    private final StockTransactionMapper stockTransactionMapper;
    private final RoleValidator roleValidator;
    private final AuditEventLogService auditEventLogService;
    /** QA D157: the witness must hold an active stock role in the same hospital. */
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    /** Roles whose holder may witness a controlled-substance movement (QA D157). */
    static final Set<String> WITNESS_ROLE_CODES = ControlledStockWitness.WITNESS_ROLE_CODES;
    /** QA D655: the one copy of the controlled-stock witness rule. */
    private final ControlledStockWitness controlledStockWitness;
    /** Re-reads the lot and item after the atomic quantity UPDATEs (#825 round 5). */
    private final EntityManager entityManager;

    @Override
    @Transactional
    public StockTransactionResponseDTO recordTransaction(StockTransactionRequestDTO dto) {
        InventoryItem item = resolveInventoryItem(dto.getInventoryItemId());
        enforceHospitalScope(item.getPharmacy());

        StockLot lot = dto.getStockLotId() != null
                ? stockLotRepository.findById(dto.getStockLotId())
                    .orElseThrow(() -> new ResourceNotFoundException("stockLot.notFound", dto.getStockLotId()))
                : null;

        requireMovementIsAccountable(dto, item, lot);
        if (dto.getTransactionType() == StockTransactionType.RETURN) {
            requireWholeUnitsWhenCounted(item, dto.getQuantity()); // QA D668
            // QA D604: the dispense row first (the lock its cancel takes), then the item.
            requireReturnWithinDispense(dto, item, lot);
            requireReturnWithinIssued(item, lot, dto.getQuantity());
        }

        User performer = resolvePerformer(dto.getPerformedBy());
        User witness = resolveWitness(dto, item, performer);

        StockTransaction tx = stockTransactionMapper.toEntity(dto, item, lot, performer);
        tx.setWitnessedByUser(witness);
        StockTransaction saved = stockTransactionRepository.save(tx);

        // Update inventory and lot quantities based on transaction type
        applyQuantityChange(item, lot, dto.getTransactionType(), dto.getQuantity());

        // Audit
        AuditEventType auditType = mapToAuditType(dto.getTransactionType());
        logAudit(auditType,
                dto.getTransactionType() + ": " + dto.getQuantity() + " units"
                        + (dto.getReason() != null ? " — " + dto.getReason() : ""),
                saved.getId().toString());

        return stockTransactionMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public StockTransactionResponseDTO getTransaction(UUID id) {
        StockTransaction tx = stockTransactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("stockTransaction.notFound", id));
        enforceHospitalScope(tx.getInventoryItem().getPharmacy());
        return stockTransactionMapper.toResponseDTO(tx);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockTransactionResponseDTO> listByInventoryItem(UUID inventoryItemId, Pageable pageable) {
        InventoryItem item = resolveInventoryItem(inventoryItemId);
        enforceHospitalScope(item.getPharmacy());
        return stockTransactionRepository.findByInventoryItemId(inventoryItemId, pageable)
                .map(stockTransactionMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockTransactionResponseDTO> listByPharmacy(UUID pharmacyId, Pageable pageable) {
        enforcePharmacyScope(pharmacyId);
        return stockTransactionRepository.findByInventoryItemPharmacyId(pharmacyId, pageable)
                .map(stockTransactionMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockTransactionResponseDTO> listByPharmacyAndDateRange(
            UUID pharmacyId, LocalDateTime from, LocalDateTime to, Pageable pageable) {
        enforcePharmacyScope(pharmacyId);
        return stockTransactionRepository.findByPharmacyAndDateRange(pharmacyId, from, to, pageable)
                .map(stockTransactionMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockTransactionResponseDTO> listByPharmacyAndType(
            UUID pharmacyId, StockTransactionType type, Pageable pageable) {
        enforcePharmacyScope(pharmacyId);
        return stockTransactionRepository.findByInventoryItemPharmacyIdAndTransactionType(
                        pharmacyId, type, pageable)
                .map(stockTransactionMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockWitnessCandidateDTO> listWitnessCandidates() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            return List.of();
        }
        UUID self = roleValidator.getCurrentUserId();
        Map<UUID, StockWitnessCandidateDTO> byUser = new LinkedHashMap<>();
        for (UserRoleHospitalAssignment a : assignmentRepository.findStaffRowsByHospitalId(hospitalId)) {
            User user = a.getUser();
            if (!Boolean.TRUE.equals(a.getActive()) || user == null || !user.isActive() || user.isDeleted()
                    || a.getRole() == null
                    || user.getId() == null || user.getId().equals(self)
                    || !WITNESS_ROLE_CODES.contains(upper(a.getRole().getCode()))) {
                continue;
            }
            byUser.putIfAbsent(user.getId(), new StockWitnessCandidateDTO(user.getId(), displayName(user)));
        }
        return byUser.values().stream()
                .sorted(Comparator.comparing(StockWitnessCandidateDTO::displayName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * D82: a manual stock movement must be accountable and keep the lots and
     * the item total in step. Morphine was "dispensed" here with no
     * prescription, and adjusted +100 with no lot and no reason, leaving the
     * item at 145 while its only lot held 50.
     *
     * <ul>
     *   <li>DISPENSE is refused: medication leaves stock through the dispense
     *       workflow, which is tied to a prescription, never from here.</li>
     *   <li>Every movement other than a receipt carries a reason.</li>
     *   <li>An item that is tracked in lots moves through a lot, and the lot
     *       must be that item's own.</li>
     *   <li>Quantities are positive; an adjustment is signed but not zero.</li>
     *   <li>Nothing is received into an expired lot (D108).</li>
     * </ul>
     */
    private void requireMovementIsAccountable(StockTransactionRequestDTO dto, InventoryItem item, StockLot lot) {
        StockTransactionType type = dto.getTransactionType();
        if (type == StockTransactionType.DISPENSE) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.dispense.viaWorkflow"));
        }
        // QA D615: a receipt here created no lot row, so an expired lot, a
        // lot number re-used with another expiry and an item total above its
        // lots all went through. Goods are received through the lot receipt
        // (POST /pharmacy/inventory/lots), which owns those rules; the portal
        // already uses it.
        if (type == StockTransactionType.RECEIPT) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.receipt.viaLots"));
        }
        BigDecimal quantity = dto.getQuantity();
        boolean quantityValid = quantity != null
                && (type == StockTransactionType.ADJUSTMENT ? quantity.signum() != 0 : quantity.signum() > 0);
        if (!quantityValid) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.quantity.invalid"));
        }
        if (dto.getReason() == null || dto.getReason().isBlank()) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.reason.required"));
        }
        if (lot != null) {
            if (lot.getInventoryItem() == null || !item.getId().equals(lot.getInventoryItem().getId())) {
                throw new BusinessException(MessageUtil.resolve("stockTransaction.lot.wrongItem"));
            }
        } else if (stockLotRepository.existsByInventoryItemId(item.getId())) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.lot.required"));
        }
    }

    /** Movements that take stock off a lot or item, which a return may put back. */
    private static final Set<StockTransactionType> ISSUED_TYPES =
            java.util.EnumSet.of(StockTransactionType.DISPENSE, StockTransactionType.TRANSFER);
    private static final Set<StockTransactionType> RETURNED_TYPES =
            java.util.EnumSet.of(StockTransactionType.RETURN);

    /**
     * QA D270 (bypass of D208): a manual RETURN put any quantity back, and a
     * lot received at 200 held 100 186. A return gives back what left: it is
     * bounded by what was dispensed or transferred out of that lot (or, for
     * an item not tracked in lots, that item) and not already returned. The
     * item row is locked first so concurrent returns are counted in turn.
     */
    private void requireReturnWithinIssued(InventoryItem item, StockLot lot, BigDecimal quantity) {
        inventoryItemRepository.lockById(item.getId());
        BigDecimal issued;
        BigDecimal returned;
        if (lot != null) {
            issued = stockTransactionRepository.sumQuantityForLot(lot.getId(), ISSUED_TYPES);
            returned = stockTransactionRepository.sumQuantityForLot(lot.getId(), RETURNED_TYPES);
        } else {
            issued = stockTransactionRepository.sumQuantityForItem(item.getId(), ISSUED_TYPES);
            returned = stockTransactionRepository.sumQuantityForItem(item.getId(), RETURNED_TYPES);
        }
        BigDecimal returnable = nz(issued).subtract(nz(returned));
        if (quantity.compareTo(returnable) > 0) {
            throw new BusinessException("stockTransaction.return.exceedsIssued",
                    returnable.max(BigDecimal.ZERO).stripTrailingZeros().toPlainString());
        }
    }

    /**
     * QA D604: a return that names a dispense ({@code referenceId}) gives
     * back at most what that dispense delivered and was not returned yet
     * (a 4-unit fill took returns of 2, 3 and 4). The dispense row is locked
     * so two returns, or a return and the cancel of that fill, are counted
     * in turn. A cancelled fill already went back to stock, so nothing more
     * can be returned against it. A reference that is not a dispense (a
     * transfer) keeps the lot / item bound below.
     */
    private void requireReturnWithinDispense(StockTransactionRequestDTO dto, InventoryItem item, StockLot lot) {
        if (dto.getReferenceId() == null) {
            attributeUnreferencedReturn(dto, item, lot);
            return;
        }
        if (dispenseRepository == null) {
            return;
        }
        com.example.hms.model.pharmacy.Dispense dispense =
                dispenseRepository.findLockedById(dto.getReferenceId()).orElse(null);
        if (dispense == null) {
            requireReturnOfTransfer(dto, item, lot);
            return;
        }
        if (dispense.getStatus() != com.example.hms.enums.DispenseStatus.COMPLETED
                && dispense.getStatus() != com.example.hms.enums.DispenseStatus.PARTIAL) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.return.dispenseNotReturnable"));
        }
        StockLot dispensedLot = dispense.getStockLot();
        boolean sameStock = dispensedLot != null
                ? lot != null && dispensedLot.getId().equals(lot.getId())
                : lot == null && dispense.getPharmacy() != null && item.getPharmacy() != null
                    && dispense.getPharmacy().getId() != null
                    && dispense.getPharmacy().getId().equals(item.getPharmacy().getId());
        if (!sameStock) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.return.wrongDispense"));
        }
        BigDecimal returnable = nz(dispense.getQuantityDispensed()).subtract(nz(
                stockTransactionRepository.sumQuantityForReference(dispense.getId(), RETURNED_TYPES)));
        if (dto.getQuantity().compareTo(returnable) > 0) {
            throw new BusinessException("stockTransaction.return.exceedsDispensed",
                    returnable.max(BigDecimal.ZERO).stripTrailingZeros().toPlainString());
        }
    }

    /**
     * QA D668/D675: a referenceId that is not a dispense (a prescription id,
     * a random UUID) was accepted and created stock. It must then be a
     * TRANSFER out of this lot (or item, for an unlotted item), and the
     * return is capped at what that transfer moved and has not come back.
     */
    private void requireReturnOfTransfer(StockTransactionRequestDTO dto, InventoryItem item, StockLot lot) {
        StockTransaction transfer = stockTransactionRepository.findById(dto.getReferenceId())
                .filter(t -> t.getTransactionType() == StockTransactionType.TRANSFER)
                .orElseThrow(() -> new BusinessException(
                        MessageUtil.resolve("stockTransaction.return.referenceNotReturnable")));
        boolean sameStock = transfer.getStockLot() != null
                ? lot != null && transfer.getStockLot().getId().equals(lot.getId())
                : lot == null && transfer.getInventoryItem() != null
                    && item.getId().equals(transfer.getInventoryItem().getId());
        if (!sameStock) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.return.referenceNotReturnable"));
        }
        BigDecimal returnable = nz(transfer.getQuantity()).subtract(nz(
                stockTransactionRepository.sumQuantityForReference(transfer.getId(), RETURNED_TYPES)));
        if (dto.getQuantity().compareTo(returnable) > 0) {
            throw new BusinessException("stockTransaction.return.exceedsDispensed",
                    returnable.max(BigDecimal.ZERO).stripTrailingZeros().toPlainString());
        }
    }

    /**
     * QA D653 then D668: a return that names no dispense was booked against
     * whichever open fill could take it, another prescription's included
     * (round-9 re-test). A return of a dispensed item names its dispense
     * now; a return without one only gives back stock that left this lot
     * (or item) by an unreferenced transfer and has not come back yet.
     */
    private void attributeUnreferencedReturn(StockTransactionRequestDTO dto, InventoryItem item, StockLot lot) {
        inventoryItemRepository.lockById(item.getId());
        BigDecimal transferred;
        BigDecimal returned;
        if (lot != null) {
            transferred = stockTransactionRepository.sumQuantityForLot(lot.getId(), TRANSFERRED_TYPES);
            returned = stockTransactionRepository.sumUnreferencedQuantityForLot(lot.getId(), RETURNED_TYPES);
        } else {
            transferred = stockTransactionRepository.sumQuantityForItem(item.getId(), TRANSFERRED_TYPES);
            returned = stockTransactionRepository.sumUnreferencedQuantityForItem(item.getId(), RETURNED_TYPES);
        }
        if (dto.getQuantity().compareTo(nz(transferred).subtract(nz(returned))) > 0) {
            throw new BusinessException("stockTransaction.return.dispenseRequired");
        }
    }

    private static final Set<StockTransactionType> TRANSFERRED_TYPES =
            java.util.EnumSet.of(StockTransactionType.TRANSFER);

    /**
     * QA D668: a return of 0.5 was accepted on a lot counted in tablets.
     * An item whose unit is not a measure (ml, g, ...) is counted in whole
     * units, so its returns are whole too.
     */
    static void requireWholeUnitsWhenCounted(InventoryItem item, BigDecimal quantity) {
        if (quantity == null || quantity.stripTrailingZeros().scale() <= 0) {
            return;
        }
        String unit = item.getUnit() == null ? "" : item.getUnit().trim().toLowerCase(java.util.Locale.ROOT);
        if (!MEASURED_UNITS.contains(unit)) {
            throw new BusinessException("stockTransaction.return.wholeUnitsOnly");
        }
    }

    /** Units a quantity is measured (not counted) in; anything else is counted. */
    static final Set<String> MEASURED_UNITS = Set.of(
            "ml", "l", "cl", "dl", "g", "mg", "kg", "mcg", "µg", "ug", "ui", "iu");

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * QA D157 (D82 follow-up), D655: a manual movement of a controlled
     * substance, a receipt included, is witnessed (the rule lives in
     * {@link ControlledStockWitness}).
     */
    private User resolveWitness(StockTransactionRequestDTO dto, InventoryItem item, User performer) {
        if (!ControlledStockWitness.isControlled(item)) {
            return null;
        }
        return controlledStockWitness.resolve(dto.getWitnessedBy(), item, performer);
    }

    private static String upper(String code) {
        return code == null ? "" : code.toUpperCase(Locale.ROOT);
    }

    private static String displayName(User user) {
        String full = (Objects.toString(user.getFirstName(), "").trim() + " "
                + Objects.toString(user.getLastName(), "").trim()).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }

    private InventoryItem resolveInventoryItem(UUID id) {
        return inventoryItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("inventoryItem.notFound", id));
    }

    private User resolvePerformer(UUID performedBy) {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("stock.user.performingUnknown");
        }
        // D82: the movement is recorded against the signed-in user. A
        // caller-supplied performer let one user book a movement in a
        // colleague's name.
        if (performedBy != null && !performedBy.equals(userId)) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.performer.notSelf"));
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));
    }

    private void enforceHospitalScope(Pharmacy pharmacy) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null && pharmacy.getHospital() != null
                && !pharmacy.getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException("pharmacy.notFound", pharmacy.getId());
        }
    }

    private void enforcePharmacyScope(UUID pharmacyId) {
        Pharmacy pharmacy = pharmacyRepository.findById(pharmacyId)
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notFound", pharmacyId));
        enforceHospitalScope(pharmacy);
    }

    /**
     * Moves the quantities in the database, never by writing back a value
     * read earlier (#825 round 5): an adjustment racing a fill or a return of
     * the same lot used to overwrite it (lot 10, adjustment +5 reads 10, the
     * fill commits 9, the adjustment saved 15 instead of 14). Same queries
     * and same order as the dispense path: the lot first, then the item, so
     * the two paths cannot deadlock against each other.
     */
    private void applyQuantityChange(InventoryItem item, StockLot lot,
                                     StockTransactionType type, BigDecimal quantity) {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        switch (type) {
            case DISPENSE, TRANSFER -> takeOff(item, lot, quantity, now);
            case RECEIPT, RETURN -> putOn(item, lot, quantity, now);
            // QA D143: a negative adjustment (a write-off) goes through the
            // guarded decrement, so it can never drive a lot or the item
            // below zero; a positive one adds.
            case ADJUSTMENT -> {
                if (quantity.signum() < 0) {
                    takeOff(item, lot, quantity.negate(), now);
                } else {
                    putOnWithinReceived(item, lot, quantity, now);
                }
            }
        }
        refreshIfManaged(lot);
        refreshIfManaged(item);
    }

    private void takeOff(InventoryItem item, StockLot lot, BigDecimal quantity, LocalDateTime now) {
        if (lot != null && stockLotRepository.decrementRemaining(lot.getId(), quantity, now) == 0) {
            throw new BusinessException("stock.lot.insufficient", lot.getLotNumber(), String.valueOf(quantity));
        }
        if (inventoryItemRepository.decrementOnHand(item.getId(), quantity, now) == 0) {
            throw new BusinessException("stock.item.insufficient", String.valueOf(quantity));
        }
    }

    /**
     * QA D405: a positive adjustment of a lot cannot take it above what it
     * received (lot 50 adjusted to 9999). Checked in the UPDATE itself, so a
     * concurrent return cannot slip past it.
     */
    private void putOnWithinReceived(InventoryItem item, StockLot lot, BigDecimal quantity, LocalDateTime now) {
        if (lot != null && stockLotRepository.incrementRemainingWithinReceived(lot.getId(), quantity, now) == 0) {
            throw new BusinessException(MessageUtil.resolve("stockLot.quantity.aboveReceived"));
        }
        inventoryItemRepository.incrementOnHand(item.getId(), quantity, now);
    }

    private void putOn(InventoryItem item, StockLot lot, BigDecimal quantity, LocalDateTime now) {
        if (lot != null) {
            stockLotRepository.incrementRemaining(lot.getId(), quantity, now);
        }
        inventoryItemRepository.incrementOnHand(item.getId(), quantity, now);
    }

    private void refreshIfManaged(Object entity) {
        if (entity != null && entityManager != null && entityManager.contains(entity)) {
            entityManager.refresh(entity);
        }
    }

    private AuditEventType mapToAuditType(StockTransactionType type) {
        return switch (type) {
            case RECEIPT -> AuditEventType.STOCK_RECEIPT;
            case ADJUSTMENT -> AuditEventType.STOCK_ADJUSTMENT;
            case TRANSFER -> AuditEventType.STOCK_TRANSFER;
            case RETURN -> AuditEventType.STOCK_RETURN;
            case DISPENSE -> AuditEventType.STOCK_ADJUSTMENT;
        };
    }

    private void logAudit(AuditEventType eventType, String description, String resourceId) {
        try {
            UUID userId = roleValidator.getCurrentUserId();
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(userId)
                    .eventType(eventType)
                    .eventDescription(description)
                    .status(AuditStatus.SUCCESS)
                    .resourceId(resourceId)
                    .entityType("STOCK_TRANSACTION")
                    .build());
        } catch (Exception e) {
            log.warn("Failed to log audit event {}: {}", eventType, e.getMessage());
        }
    }
}
