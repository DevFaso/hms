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
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

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

    @Override
    @Transactional
    public StockTransactionResponseDTO recordTransaction(StockTransactionRequestDTO dto) {
        InventoryItem item = resolveInventoryItem(dto.getInventoryItemId());
        enforceHospitalScope(item.getPharmacy());

        StockLot lot = dto.getStockLotId() != null
                ? stockLotRepository.findById(dto.getStockLotId())
                    .orElseThrow(() -> new ResourceNotFoundException("Stock lot not found"))
                : null;

        User performer = resolvePerformer(dto.getPerformedBy());

        StockTransaction tx = stockTransactionMapper.toEntity(dto, item, lot, performer);
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
                .orElseThrow(() -> new ResourceNotFoundException("Stock transaction not found"));
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

    // ── Private helpers ──────────────────────────────────────────────────

    private InventoryItem resolveInventoryItem(UUID id) {
        return inventoryItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory item not found"));
    }

    private User resolvePerformer(UUID performedBy) {
        if (performedBy != null) {
            return userRepository.findById(performedBy)
                    .orElseThrow(() -> new ResourceNotFoundException("Performer user not found"));
        }
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("Unable to determine performing user");
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

    private void enforcePharmacyScope(UUID pharmacyId) {
        Pharmacy pharmacy = pharmacyRepository.findById(pharmacyId)
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacy not found"));
        enforceHospitalScope(pharmacy);
    }

    private void applyQuantityChange(InventoryItem item, StockLot lot,
                                     StockTransactionType type, BigDecimal quantity) {
        switch (type) {
            case RECEIPT -> {
                item.setQuantityOnHand(item.getQuantityOnHand().add(quantity));
                if (lot != null) {
                    lot.setRemainingQuantity(lot.getRemainingQuantity().add(quantity));
                    stockLotRepository.save(lot);
                }
            }
            case DISPENSE, TRANSFER -> {
                validateSufficientStock(item, quantity);
                item.setQuantityOnHand(item.getQuantityOnHand().subtract(quantity));
                if (lot != null) {
                    validateSufficientLotStock(lot, quantity);
                    lot.setRemainingQuantity(lot.getRemainingQuantity().subtract(quantity));
                    stockLotRepository.save(lot);
                }
            }
            case ADJUSTMENT -> {
                // Quantity can be positive (increase) or negative (decrease)
                item.setQuantityOnHand(item.getQuantityOnHand().add(quantity));
                if (lot != null) {
                    lot.setRemainingQuantity(lot.getRemainingQuantity().add(quantity));
                    stockLotRepository.save(lot);
                }
            }
            case RETURN -> {
                item.setQuantityOnHand(item.getQuantityOnHand().add(quantity));
                if (lot != null) {
                    lot.setRemainingQuantity(lot.getRemainingQuantity().add(quantity));
                    stockLotRepository.save(lot);
                }
            }
        }
        inventoryItemRepository.save(item);
    }

    private void validateSufficientStock(InventoryItem item, BigDecimal quantity) {
        if (item.getQuantityOnHand().compareTo(quantity) < 0) {
            throw new BusinessException("Insufficient stock: " + item.getQuantityOnHand()
                    + " on hand, requested " + quantity);
        }
    }

    private void validateSufficientLotStock(StockLot lot, BigDecimal quantity) {
        if (lot.getRemainingQuantity().compareTo(quantity) < 0) {
            throw new BusinessException("Insufficient lot stock: " + lot.getRemainingQuantity()
                    + " remaining in lot " + lot.getLotNumber() + ", requested " + quantity);
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
