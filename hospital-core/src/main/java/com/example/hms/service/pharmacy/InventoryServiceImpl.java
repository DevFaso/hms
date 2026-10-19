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
import java.util.List;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryServiceImpl implements InventoryService {

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
    /** Re-reads an entity after an atomic quantity UPDATE (#825 round 5). */
    private final EntityManager entityManager;

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
                throw new BusinessException("Inventory item already exists for this medication at this pharmacy");
            }
        }
        // QA D208: stock enters through a goods receipt (lot + ledger), never
        // as an opening number typed on the item.
        if (dto.getQuantityOnHand() != null && dto.getQuantityOnHand().signum() != 0) {
            throw new BusinessException(
                "A new inventory item starts at zero; receive stock through a goods receipt with a lot.");
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
            throw new BusinessException(
                "The quantity on hand changes only through a stock transaction (receipt or adjustment).");
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

    @Override
    @Transactional
    public StockLotResponseDTO receiveStock(StockLotRequestDTO dto) {
        InventoryItem item = resolveInventoryItem(dto.getInventoryItemId());
        enforceHospitalScope(item.getPharmacy());

        // D108: an expired lot is not stock. It was accepted at goods receipt
        // and counted on hand, where the FEFO picker skips it but every total,
        // reorder decision and report counted it.
        if (dto.getExpiryDate() != null && dto.getExpiryDate().isBefore(LocalDate.now(ZoneId.systemDefault()))) {
            throw new BusinessException(
                com.example.hms.utility.MessageUtil.resolve("stockLot.expired"));
        }

        // The receipt is recorded against the signed-in user (D82): a
        // caller-supplied receiver let one user book a delivery in a
        // colleague's name.
        User receivedByUser = resolveCurrentUser();
        if (dto.getReceivedBy() != null && !dto.getReceivedBy().equals(receivedByUser.getId())) {
            throw new BusinessException(
                com.example.hms.utility.MessageUtil.resolve("stockTransaction.performer.notSelf"));
        }

        StockLot lot = stockLotMapper.toEntity(dto, item, receivedByUser);
        StockLot savedLot = stockLotRepository.save(lot);

        // Update quantity on hand: atomically, so a fill or a return of the
        // same item at the same moment is not overwritten (#825 round 5).
        inventoryItemRepository.incrementOnHand(item.getId(), savedLot.getRemainingQuantity(), LocalDateTime.now(ZoneId.systemDefault()));
        refreshIfManaged(item);

        // Record stock transaction
        recordStockTransaction(item, savedLot, StockTransactionType.RECEIPT,
                savedLot.getRemainingQuantity(), "Stock receipt: lot " + savedLot.getLotNumber());

        // Audit
        logAudit(AuditEventType.STOCK_RECEIPT,
                "Stock received: " + savedLot.getRemainingQuantity() + " units, lot " + savedLot.getLotNumber(),
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
        stockLotMapper.updateEntity(lot, dto);
        StockLot saved = stockLotRepository.save(lot);
        if (dto.getRemainingQuantity() != null) {
            // A count correction, applied atomically (#825 round 5).
            LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
            stockLotRepository.setRemaining(saved.getId(), dto.getRemainingQuantity(), now);
            refreshIfManaged(saved);
            // D82: the item total moves with the lot, and the correction is a
            // recorded ADJUSTMENT. It used to change the lot alone, silently,
            // so the lots and the item total drifted apart.
            BigDecimal delta = remainingBefore != null
                    ? dto.getRemainingQuantity().subtract(remainingBefore) : BigDecimal.ZERO;
            if (delta.signum() != 0) {
                InventoryItem item = lot.getInventoryItem();
                inventoryItemRepository.incrementOnHand(item.getId(), delta, now);
                refreshIfManaged(item);
                recordStockTransaction(item, saved, StockTransactionType.ADJUSTMENT, delta,
                        "Lot count correction: lot " + saved.getLotNumber());
            }
        }
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
        Locale locale = LocaleContextHolder.getLocale();
        String unknown = messageSource.getMessage("inventory.lowStock.unknown", null, locale);
        for (InventoryItem item : lowStock) {
            String medicationName = item.getMedicationCatalogItem() != null
                    ? item.getMedicationCatalogItem().getNameFr() : unknown;
            String pharmacyName = item.getPharmacy() != null
                    ? item.getPharmacy().getName() : unknown;
            String message = messageSource.getMessage("inventory.lowStock.notification",
                    new Object[]{medicationName, pharmacyName,
                        String.valueOf(item.getQuantityOnHand()), String.valueOf(item.getReorderThreshold())},
                    locale);

            try {
                UUID currentUserId = roleValidator.getCurrentUserId();
                if (currentUserId != null) {
                    User user = userRepository.findById(currentUserId).orElse(null);
                    if (user != null) {
                        notificationService.createNotification(message, user.getUsername(), "REORDER_ALERT");
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to send reorder alert for item {}: {}", item.getId(), e.getMessage());
            }

            logAudit(AuditEventType.STOCK_REORDER_ALERT, message, item.getId().toString());
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

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
                                        StockTransactionType type, BigDecimal quantity, String reason) {
        User performer = resolveCurrentUser();
        StockTransaction tx = StockTransaction.builder()
                .inventoryItem(item)
                .stockLot(lot)
                .transactionType(type)
                .quantity(quantity)
                .reason(reason)
                .performedByUser(performer)
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
