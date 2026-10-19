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
import java.time.LocalDate;
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
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockTransactionServiceImpl implements StockTransactionService {

    private final StockTransactionRepository stockTransactionRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final StockLotRepository stockLotRepository;
    private final PharmacyRepository pharmacyRepository;
    private final UserRepository userRepository;
    private final StockTransactionMapper stockTransactionMapper;
    private final RoleValidator roleValidator;
    private final AuditEventLogService auditEventLogService;
    /** QA D157: the witness must hold an active stock role in the same hospital. */
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    /** Roles whose holder may witness a controlled-substance movement (QA D157). */
    static final Set<String> WITNESS_ROLE_CODES =
            Set.of("ROLE_PHARMACIST", "ROLE_STORE_MANAGER", "ROLE_HOSPITAL_ADMIN");
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
        BigDecimal quantity = dto.getQuantity();
        boolean quantityValid = quantity != null
                && (type == StockTransactionType.ADJUSTMENT ? quantity.signum() != 0 : quantity.signum() > 0);
        if (!quantityValid) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.quantity.invalid"));
        }
        if (type != StockTransactionType.RECEIPT && (dto.getReason() == null || dto.getReason().isBlank())) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.reason.required"));
        }
        if (lot != null) {
            if (lot.getInventoryItem() == null || !item.getId().equals(lot.getInventoryItem().getId())) {
                throw new BusinessException(MessageUtil.resolve("stockTransaction.lot.wrongItem"));
            }
            if (type == StockTransactionType.RECEIPT && lot.getExpiryDate() != null
                    && lot.getExpiryDate().isBefore(LocalDate.now(ZoneId.systemDefault()))) {
                throw new BusinessException(MessageUtil.resolve("stockLot.expired"));
            }
        } else if (stockLotRepository.existsByInventoryItemId(item.getId())) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.lot.required"));
        }
    }

    /**
     * QA D157 (D82 follow-up): a manual movement of a controlled substance
     * is witnessed by a second person, not the performer, who holds an
     * active stock role in the hospital that owns the stock. Any other
     * movement records no witness, so the column means one thing.
     */
    private User resolveWitness(StockTransactionRequestDTO dto, InventoryItem item, User performer) {
        boolean controlled = dto.getTransactionType() != StockTransactionType.RECEIPT && isControlled(item);
        if (!controlled) {
            return null;
        }
        UUID witnessId = dto.getWitnessedBy();
        if (witnessId == null) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.witness.required"));
        }
        if (witnessId.equals(performer.getId())) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.witness.notSelf"));
        }
        UUID hospitalId = item.getPharmacy() != null && item.getPharmacy().getHospital() != null
                ? item.getPharmacy().getHospital().getId() : null;
        if (hospitalId == null || !assignmentRepository.existsActiveWitness(
                witnessId, hospitalId, WITNESS_ROLE_CODES)) {
            throw new BusinessException(MessageUtil.resolve("stockTransaction.witness.invalid"));
        }
        return userRepository.findById(witnessId)
                .orElseThrow(() -> new BusinessException(MessageUtil.resolve("stockTransaction.witness.invalid")));
    }

    private static boolean isControlled(InventoryItem item) {
        MedicationCatalogItem catalogItem = item.getMedicationCatalogItem();
        return catalogItem != null && catalogItem.isControlled();
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
                    putOn(item, lot, quantity, now);
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
