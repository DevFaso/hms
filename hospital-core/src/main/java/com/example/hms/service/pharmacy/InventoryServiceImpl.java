package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.InventoryItemMapper;
import com.example.hms.mapper.pharmacy.StockLotMapper;
import com.example.hms.mapper.pharmacy.StockTransactionMapper;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pharmacy.InventoryItemRequestDTO;
import com.example.hms.payload.dto.pharmacy.InventoryItemResponseDTO;
import com.example.hms.payload.dto.pharmacy.StockLotRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockLotResponseDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.NotificationService;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
import com.example.hms.validation.PickedDayNotInFutureValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryServiceImpl implements InventoryService {

    /** QA D733: the most one goods receipt may count in (per lot line). */
    static final BigDecimal MAX_RECEIPT_QUANTITY = new BigDecimal("1000000");


    private final InventoryItemRepository inventoryItemRepository;
    private final StockLotRepository stockLotRepository;
    private final StockTransactionRepository stockTransactionRepository;
    private final PharmacyRepository pharmacyRepository;
    private final MedicationCatalogItemRepository medicationCatalogItemRepository;
    private final UserRepository userRepository;
    private final InventoryItemMapper inventoryItemMapper;
    private final StockLotMapper stockLotMapper;
    private final StockTransactionMapper stockTransactionMapper;
    private final RoleValidator roleValidator;
    private final AuditEventLogService auditEventLogService;
    private final NotificationService notificationService;
    private final MessageSource messageSource;
    /** QA D655: a controlled substance is received in front of a witness. */
    private final ControlledStockWitness controlledStockWitness;
    /** Re-reads an entity after an atomic quantity UPDATE (#825 round 5). */
    private final EntityManager entityManager;
    /** The server's clock (UTC), for the picked-day check of a receipt. */
    private final Clock clock;

    // ── Inventory items ──────────────────────────────────────────────────

    @Override
    @Transactional
    public InventoryItemResponseDTO createInventoryItem(InventoryItemRequestDTO dto) {
        Pharmacy pharmacy = resolvePharmacy(dto.getPharmacyId());
        enforceHospitalScope(pharmacy);

        MedicationCatalogItem medication = resolveMedication(dto.getMedicationCatalogItemId());

        inventoryItemRepository.findByPharmacyIdAndMedicationCatalogItemId(
                pharmacy.getId(), medication.getId())
            .ifPresent(existing -> {
                throw new BusinessException("stock.inventory.duplicate");
            });
        // QA D208: the same drug under another catalogue row (same molecule,
        // strength and form) is the same stock item; a second one splits the
        // count and doubles every picker.
        String key = sameDrugKey(medication);
        if (key != null) {
            boolean twin = inventoryItemRepository.findByPharmacyIdAndActiveTrue(pharmacy.getId(),
                    org.springframework.data.domain.Pageable.unpaged()).stream()
                .anyMatch(existing -> key.equals(sameDrugKey(existing.getMedicationCatalogItem())));
            if (twin) {
                throw new BusinessException("stock.inventory.duplicate");
            }
        }
        // QA D208: stock enters through a goods receipt (lot + ledger), never
        // as an opening number typed on the item.
        if (dto.getQuantityOnHand() != null && dto.getQuantityOnHand().signum() != 0) {
            throw new BusinessException("stock.inventory.openingQuantity");
        }

        InventoryItem entity = inventoryItemMapper.toEntity(dto, pharmacy, medication);
        entity.setQuantityOnHand(java.math.BigDecimal.ZERO);
        InventoryItem saved = inventoryItemRepository.save(entity);
        return inventoryItemMapper.toResponseDTO(saved);
    }

    /**
     * QA D208: molecule + strength + form, accent- and case-insensitive, or
     * null when the catalogue row does not name its molecule.
     */
    private static String sameDrugKey(MedicationCatalogItem medication) {
        if (medication == null) {
            return null;
        }
        String molecule = medication.getGenericName() != null && !medication.getGenericName().isBlank()
            ? medication.getGenericName() : medication.getNameFr();
        if (molecule == null || molecule.isBlank()) {
            return null;
        }
        return fold(molecule) + "|" + fold(medication.getStrength()) + "|" + fold(medication.getStrengthUnit())
            + "|" + fold(medication.getForm());
    }

    private static String fold(String value) {
        if (value == null) {
            return "";
        }
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryItemResponseDTO getInventoryItem(UUID id) {
        InventoryItem item = resolveInventoryItem(id);
        enforceHospitalScope(item.getPharmacy());
        return inventoryItemMapper.toResponseDTO(item);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InventoryItemResponseDTO> listByPharmacy(UUID pharmacyId, Pageable pageable) {
        Pharmacy pharmacy = resolvePharmacy(pharmacyId);
        enforceHospitalScope(pharmacy);
        return inventoryItemRepository.findByPharmacyIdAndActiveTrue(pharmacyId, pageable)
                .map(inventoryItemMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InventoryItemResponseDTO> listByHospital(Pageable pageable) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            return inventoryItemRepository.findAll(pageable)
                    .map(inventoryItemMapper::toResponseDTO);
        }
        return inventoryItemRepository.findByPharmacyHospitalIdAndActiveTrue(hospitalId, pageable)
                .map(inventoryItemMapper::toResponseDTO);
    }

    @Override
    @Transactional
    public InventoryItemResponseDTO updateInventoryItem(UUID id, InventoryItemRequestDTO dto) {
        InventoryItem item = resolveInventoryItem(id);
        enforceHospitalScope(item.getPharmacy());
        // QA D208: a quantity typed on the item moved stock with no ledger
        // row. A count correction is an ADJUSTMENT transaction (stock
        // adjustment screen), which writes the ledger and needs a reason.
        if (dto.getQuantityOnHand() != null && (item.getQuantityOnHand() == null
                || dto.getQuantityOnHand().compareTo(item.getQuantityOnHand()) != 0)) {
            throw new BusinessException("stock.inventory.quantityViaTransaction");
        }
        inventoryItemMapper.updateEntity(item, dto);
        InventoryItem saved = inventoryItemRepository.save(item);
        return inventoryItemMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public void deactivateInventoryItem(UUID id) {
        InventoryItem item = resolveInventoryItem(id);
        enforceHospitalScope(item.getPharmacy());
        item.setActive(false);
        inventoryItemRepository.save(item);
    }

    // ── Stock lots ───────────────────────────────────────────────────────

    /** Field checks on a goods receipt, before anything is read or written. */
    private void validateReceiptQuantitiesAndDates(StockLotRequestDTO dto) {
        // D108: an expired lot is not stock. It was accepted at goods receipt
        // and counted on hand, where the FEFO picker skips it but every total,
        // reorder decision and report counted it.
        if (dto.getExpiryDate() != null && !dto.getExpiryDate().isAfter(LocalDate.now(ZoneId.systemDefault()))) {
            // QA D710: a lot expiring today is already expired.
            throw new BusinessException(
                com.example.hms.utility.MessageUtil.resolve("stockLot.expired"));
        }

        // QA D341: a receipt adds stock; a zero or negative quantity reached
        // the database CHECK and came back as a generic constraint error.
        if (dto.getInitialQuantity() == null || dto.getInitialQuantity().signum() <= 0
            || (dto.getRemainingQuantity() != null && dto.getRemainingQuantity().signum() < 0)) {
            throw new BusinessException(
                com.example.hms.utility.MessageUtil.resolve("stockLot.quantity.positive"));
        }
        // QA D733: 1e9 units were accepted; no single delivery is that size.
        if (dto.getInitialQuantity().compareTo(MAX_RECEIPT_QUANTITY) > 0) {
            throw new BusinessException(
                com.example.hms.utility.MessageUtil.resolve("stockLot.quantity.tooLarge",
                    com.example.hms.utility.DisplayNumbers.quantity(MAX_RECEIPT_QUANTITY)));
        }
        // QA D334: a delivery cannot have been received in the future. #948
        // review: the portal defaults it to the reader's today, which east of
        // UTC is already tomorrow on the server's calendar, so that day passes.
        if (!PickedDayNotInFutureValidator.isAllowed(dto.getReceivedDate(), clock)) {
            throw new BusinessException(
                com.example.hms.utility.MessageUtil.resolve("stockLot.receivedDate.future"));
        }
        // QA D405: a lot cannot be received holding more than was received.
        if (dto.getRemainingQuantity() != null && dto.getInitialQuantity() != null
            && dto.getRemainingQuantity().compareTo(dto.getInitialQuantity()) > 0) {
            throw new BusinessException(
                com.example.hms.utility.MessageUtil.resolve("stockLot.quantity.aboveReceived"));
        }
    }

    private void requireConsistentLotExpiry(InventoryItem item, StockLotRequestDTO dto) {
        // QA D315: a lot number identifies one manufacturing batch, which has
        // one expiry date. A second delivery of the same lot is fine; the same
        // lot number with another expiry is a typing error or a false label.
        if (dto.getLotNumber() != null && dto.getExpiryDate() != null
            && stockLotRepository.existsOtherExpiryForLot(item.getId(), dto.getLotNumber().trim(),
                dto.getExpiryDate())) {
            throw new BusinessException(
                com.example.hms.utility.MessageUtil.resolve("stockLot.lotNumber.expiryMismatch"));
        }
    }

    @Override
    @Transactional
    public StockLotResponseDTO receiveStock(StockLotRequestDTO dto) {
        InventoryItem item = resolveInventoryItem(dto.getInventoryItemId());
        enforceHospitalScope(item.getPharmacy());

        validateReceiptQuantitiesAndDates(dto);
        // QA D779: a receipt is a stock movement like any other (D668 guarded
        // only returns): 2.5 tablets left the item at 3001182.5 on hand.
        WholeUnitQuantity.require(item, dto.getInitialQuantity());
        WholeUnitQuantity.require(item, dto.getRemainingQuantity());
        // QA D778: the DTO constraint is the first gate; this one holds for
        // any caller that skips bean validation.
        if (dto.getUnitCost() != null && dto.getUnitCost().signum() < 0) {
            throw new BusinessException("stockTransaction.unitCost.min");
        }
        requireConsistentLotExpiry(item, dto);

        // The receipt is recorded against the signed-in user (D82): a
        // caller-supplied receiver let one user book a delivery in a
        // colleague's name.
        User receivedByUser = resolveCurrentUser();
        if (dto.getReceivedBy() != null && !dto.getReceivedBy().equals(receivedByUser.getId())) {
            throw new BusinessException(
                com.example.hms.utility.MessageUtil.resolve("stockTransaction.performer.notSelf"));
        }

        // QA D655: a controlled substance is received in front of a witness,
        // as every other movement of it is (checked before anything is written).
        User witness = ControlledStockWitness.isControlled(item)
            ? controlledStockWitness.resolve(dto.getWitnessedBy(), item, receivedByUser) : null;

        // QA D733: the same lot received twice made two rows, so the FEFO
        // pick, the counts and the expiry report saw two batches. A further
        // delivery of a batch adds to its row; the item row is locked so two
        // receipts of a new lot at once still make one row.
        BigDecimal received = dto.getRemainingQuantity() != null
            ? dto.getRemainingQuantity() : dto.getInitialQuantity();
        StockLot savedLot = null;
        if (dto.getLotNumber() != null && dto.getExpiryDate() != null) {
            inventoryItemRepository.lockById(item.getId());
            List<StockLot> same = stockLotRepository.findSameLot(item.getId(), dto.getLotNumber().trim(),
                dto.getExpiryDate());
            if (!same.isEmpty()) {
                savedLot = same.get(0);
                stockLotRepository.addReceipt(savedLot.getId(), dto.getInitialQuantity(),
                    LocalDateTime.now(ZoneId.systemDefault()));
                received = dto.getInitialQuantity();
                refreshIfManaged(savedLot);
            }
        }
        if (savedLot == null) {
            StockLot lot = stockLotMapper.toEntity(dto, item, receivedByUser);
            savedLot = stockLotRepository.save(lot);
        }

        // Update quantity on hand: atomically, so a fill or a return of the
        // same item at the same moment is not overwritten (#825 round 5).
        inventoryItemRepository.incrementOnHand(item.getId(), received, LocalDateTime.now(ZoneId.systemDefault()));
        refreshIfManaged(item);

        // Record stock transaction
        recordStockTransaction(item, savedLot, StockTransactionType.RECEIPT,
                received, "Stock receipt: lot " + savedLot.getLotNumber(), witness);

        // Audit
        logAudit(AuditEventType.STOCK_RECEIPT,
                "Stock received: " + received + " units, lot " + savedLot.getLotNumber(),
                savedLot.getId().toString());

        return stockLotMapper.toResponseDTO(savedLot);
    }

    @Override
    @Transactional(readOnly = true)
    public StockLotResponseDTO getStockLot(UUID id) {
        StockLot lot = resolveStockLot(id);
        enforceHospitalScope(lot.getInventoryItem().getPharmacy());
        return stockLotMapper.toResponseDTO(lot);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockLotResponseDTO> listLotsByInventoryItem(UUID inventoryItemId, Pageable pageable) {
        InventoryItem item = resolveInventoryItem(inventoryItemId);
        enforceHospitalScope(item.getPharmacy());
        return stockLotRepository.findByInventoryItemId(inventoryItemId, pageable)
                .map(stockLotMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockLotResponseDTO> listLotsByPharmacy(UUID pharmacyId, Pageable pageable) {
        Pharmacy pharmacy = resolvePharmacy(pharmacyId);
        enforceHospitalScope(pharmacy);
        return stockLotRepository.findByInventoryItemPharmacyId(pharmacyId, pageable)
                .map(stockLotMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockLotResponseDTO> getExpiringSoon(UUID pharmacyId, int daysAhead) {
        Pharmacy pharmacy = resolvePharmacy(pharmacyId);
        enforceHospitalScope(pharmacy);
        LocalDate cutoff = LocalDate.now().plusDays(daysAhead);
        return stockLotRepository.findExpiringSoon(pharmacyId, cutoff).stream()
                .map(stockLotMapper::toResponseDTO)
                .toList();
    }

    @Override
    @Transactional
    public StockLotResponseDTO updateStockLot(UUID id, StockLotRequestDTO dto) {
        StockLot lot = resolveStockLot(id);
        enforceHospitalScope(lot.getInventoryItem().getPharmacy());
        BigDecimal remainingBefore = lot.getRemainingQuantity();
        // QA D334: the lot number and expiry are what was received and what
        // the FEFO pick and the expiry write-off run on. Changing them after
        // receipt left no ledger entry; a wrong lot is corrected by a
        // write-off and a new receipt, which the ledger records.
        if ((dto.getLotNumber() != null && !dto.getLotNumber().equals(lot.getLotNumber()))
            || (dto.getExpiryDate() != null && !dto.getExpiryDate().equals(lot.getExpiryDate()))) {
            throw new BusinessException(
                com.example.hms.utility.MessageUtil.resolve("stockLot.identity.immutable"));
        }
        // QA D405 (D82, D341 did not hold): the lot edit set any count, above
        // what was received, with no reason and no witness even for a
        // controlled substance. A count now moves only through a stock
        // transaction (ADJUSTMENT), which requires a reason, a witness for a
        // controlled substance, and stays within what the lot received. The
        // count the lot already has is accepted, so a form that echoes it
        // back still saves its other fields.
        if (dto.getRemainingQuantity() != null
                && (remainingBefore == null || dto.getRemainingQuantity().compareTo(remainingBefore) != 0)) {
            throw new BusinessException(
                com.example.hms.utility.MessageUtil.resolve("stockLot.quantity.useAdjustment"));
        }
        stockLotMapper.updateEntity(lot, dto);
        StockLot saved = stockLotRepository.save(lot);
        return stockLotMapper.toResponseDTO(saved);
    }

    // ── Reorder alerts ───────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<InventoryItemResponseDTO> getItemsBelowReorderThreshold(UUID pharmacyId) {
        Pharmacy pharmacy = resolvePharmacy(pharmacyId);
        enforceHospitalScope(pharmacy);
        return inventoryItemRepository.findBelowReorderThreshold(pharmacyId).stream()
                .map(inventoryItemMapper::toResponseDTO)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<InventoryItemResponseDTO> getItemsBelowReorderThresholdByHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            return List.of();
        }
        return inventoryItemRepository.findBelowReorderThresholdByHospital(hospitalId).stream()
                .map(inventoryItemMapper::toResponseDTO)
                .toList();
    }

    @Override
    @Transactional
    public void triggerReorderAlerts() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            return;
        }
        List<InventoryItem> lowStock = inventoryItemRepository.findBelowReorderThresholdByHospital(hospitalId);
        // The alert is pushed to the user who triggered the sweep, so the
        // recipient IS the caller and the request locale is the right one.
        // The audit row is read by everyone, each in their own language
        // (QA D894): it is written in English, the shape the portal's
        // pattern reads, whatever the caller's language.
        Locale locale = LocaleContextHolder.getLocale();
        String unknown = messageSource.getMessage("inventory.lowStock.unknown", null, locale);
        String unknownForAudit = messageSource.getMessage("inventory.lowStock.unknown", null, Locale.ENGLISH);
        for (InventoryItem item : lowStock) {
            boolean hasMedication = item.getMedicationCatalogItem() != null;
            boolean hasPharmacy = item.getPharmacy() != null;
            String onHand = String.valueOf(item.getQuantityOnHand());
            String threshold = String.valueOf(item.getReorderThreshold());
            Object[] alertArgs = {hasMedication ? item.getMedicationCatalogItem().getNameFr() : unknown,
                hasPharmacy ? item.getPharmacy().getName() : unknown, onHand, threshold};
            Object[] auditArgs = {hasMedication ? item.getMedicationCatalogItem().getNameFr() : unknownForAudit,
                hasPharmacy ? item.getPharmacy().getName() : unknownForAudit, onHand, threshold};

            pushReorderAlert(item, alertArgs);

            logAudit(AuditEventType.STOCK_REORDER_ALERT,
                messageSource.getMessage("inventory.lowStock.notification", auditArgs, Locale.ENGLISH),
                item.getId().toString());
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /** Pushes the low-stock alert to the caller who triggered the sweep; a failure never stops the sweep. */
    private void pushReorderAlert(InventoryItem item, Object[] alertArgs) {
        try {
            UUID currentUserId = roleValidator.getCurrentUserId();
            if (currentUserId == null) {
                return;
            }
            userRepository.findById(currentUserId).ifPresent(user ->
                notificationService.createNotification("inventory.lowStock.notification", alertArgs,
                    user.getUsername(), "REORDER_ALERT"));
        } catch (Exception e) {
            log.warn("Failed to send reorder alert for item {}: {}", item.getId(), e.getMessage());
        }
    }

    private Pharmacy resolvePharmacy(UUID pharmacyId) {
        return pharmacyRepository.findById(pharmacyId)
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notFound", pharmacyId));
    }

    private MedicationCatalogItem resolveMedication(UUID medicationId) {
        return medicationCatalogItemRepository.findById(medicationId)
                .orElseThrow(() -> new ResourceNotFoundException("medicationCatalogItem.notFound", medicationId));
    }

    private InventoryItem resolveInventoryItem(UUID id) {
        return inventoryItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("inventoryItem.notFound", id));
    }

    private StockLot resolveStockLot(UUID id) {
        return stockLotRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("stockLot.notFound", id));
    }

    private User resolveCurrentUser() {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("stock.user.unknown");
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

    private void recordStockTransaction(InventoryItem item, StockLot lot,
                                        StockTransactionType type, BigDecimal quantity, String reason,
                                        User witness) {
        User performer = resolveCurrentUser();
        StockTransaction tx = StockTransaction.builder()
                .inventoryItem(item)
                .stockLot(lot)
                .transactionType(type)
                .quantity(quantity)
                .reason(reason)
                .performedByUser(performer)
                .witnessedByUser(witness)
                .build();
        stockTransactionRepository.save(tx);
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
                    .entityType("INVENTORY")
                    .build());
        } catch (Exception e) {
            log.warn("Failed to log audit event {}: {}", eventType, e.getMessage());
        }
    }

    private void refreshIfManaged(Object entity) {
        if (entityManager != null && entityManager.contains(entity)) {
            entityManager.refresh(entity);
        }
    }
}
