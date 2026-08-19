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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
                throw new BusinessException("Inventory item already exists for this medication at this pharmacy");
            });

        InventoryItem entity = inventoryItemMapper.toEntity(dto, pharmacy, medication);
        InventoryItem saved = inventoryItemRepository.save(entity);
        return inventoryItemMapper.toResponseDTO(saved);
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

        User receivedByUser = dto.getReceivedBy() != null
                ? userRepository.findById(dto.getReceivedBy())
                    .orElseThrow(() -> new ResourceNotFoundException("User not found"))
                : resolveCurrentUser();

        StockLot lot = stockLotMapper.toEntity(dto, item, receivedByUser);
        StockLot savedLot = stockLotRepository.save(lot);

        // Update quantity on hand
        item.setQuantityOnHand(item.getQuantityOnHand().add(savedLot.getRemainingQuantity()));
        inventoryItemRepository.save(item);

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
        for (InventoryItem item : lowStock) {
            String medicationName = item.getMedicationCatalogItem() != null
                    ? item.getMedicationCatalogItem().getNameFr() : "Unknown";
            String pharmacyName = item.getPharmacy() != null
                    ? item.getPharmacy().getName() : "Unknown";
            String message = String.format("Low stock alert: %s at %s — %s on hand, reorder threshold %s",
                    medicationName, pharmacyName, item.getQuantityOnHand(), item.getReorderThreshold());

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
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacy not found"));
    }

    private MedicationCatalogItem resolveMedication(UUID medicationId) {
        return medicationCatalogItemRepository.findById(medicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Medication catalog item not found"));
    }

    private InventoryItem resolveInventoryItem(UUID id) {
        return inventoryItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory item not found"));
    }

    private StockLot resolveStockLot(UUID id) {
        return stockLotRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Stock lot not found"));
    }

    private User resolveCurrentUser() {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("Unable to determine current user");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Current user not found"));
    }

    private void enforceHospitalScope(Pharmacy pharmacy) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null && pharmacy.getHospital() != null
                && !pharmacy.getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException("Pharmacy not found");
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
}
