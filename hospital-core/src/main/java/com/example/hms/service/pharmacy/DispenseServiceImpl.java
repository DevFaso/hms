package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.DispenseMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseResponseDTO;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
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
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DispenseServiceImpl implements DispenseService {

    private final DispenseRepository dispenseRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final PatientRepository patientRepository;
    private final PharmacyRepository pharmacyRepository;
    private final StockLotRepository stockLotRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final StockTransactionRepository stockTransactionRepository;
    private final UserRepository userRepository;
    private final MedicationCatalogItemRepository medicationCatalogItemRepository;
    private final DispenseMapper dispenseMapper;
    private final RoleValidator roleValidator;
    private final AuditEventLogService auditEventLogService;

    private static final Set<PrescriptionStatus> DISPENSABLE_STATUSES = Set.of(
            PrescriptionStatus.SIGNED,
            PrescriptionStatus.TRANSMITTED,
            PrescriptionStatus.PARTIALLY_FILLED
    );

    @Override
    @Transactional
    public DispenseResponseDTO createDispense(DispenseRequestDTO dto) {
        Prescription prescription = prescriptionRepository.findById(dto.getPrescriptionId())
                .orElseThrow(() -> new ResourceNotFoundException("Prescription not found"));

        if (!DISPENSABLE_STATUSES.contains(prescription.getStatus())) {
            throw new BusinessException("Prescription is not in a dispensable state: " + prescription.getStatus());
        }

        Patient patient = patientRepository.findById(dto.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        Pharmacy pharmacy = pharmacyRepository.findById(dto.getPharmacyId())
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacy not found"));
        enforceHospitalScope(pharmacy);

        User dispensedByUser = userRepository.findById(dto.getDispensedBy())
                .orElseThrow(() -> new ResourceNotFoundException("Dispensed-by user not found"));

        User verifiedByUser = dto.getVerifiedBy() != null
                ? userRepository.findById(dto.getVerifiedBy())
                    .orElseThrow(() -> new ResourceNotFoundException("Verified-by user not found"))
                : null;

        MedicationCatalogItem catalogItem = dto.getMedicationCatalogItemId() != null
                ? medicationCatalogItemRepository.findById(dto.getMedicationCatalogItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Medication catalog item not found"))
                : null;

        StockLot stockLot = null;
        if (dto.getStockLotId() != null) {
            stockLot = stockLotRepository.findById(dto.getStockLotId())
                    .orElseThrow(() -> new ResourceNotFoundException("Stock lot not found"));

            // Validate sufficient lot stock
            if (stockLot.getRemainingQuantity().compareTo(dto.getQuantityDispensed()) < 0) {
                throw new BusinessException("Insufficient lot stock: "
                        + stockLot.getRemainingQuantity() + " remaining, requested "
                        + dto.getQuantityDispensed());
            }

            // Decrement stock lot
            stockLot.setRemainingQuantity(
                    stockLot.getRemainingQuantity().subtract(dto.getQuantityDispensed()));
            stockLotRepository.save(stockLot);

            // Decrement inventory item
            InventoryItem inventoryItem = stockLot.getInventoryItem();
            if (inventoryItem.getQuantityOnHand().compareTo(dto.getQuantityDispensed()) < 0) {
                throw new BusinessException("Insufficient inventory stock");
            }
            inventoryItem.setQuantityOnHand(
                    inventoryItem.getQuantityOnHand().subtract(dto.getQuantityDispensed()));
            inventoryItemRepository.save(inventoryItem);

            // Record stock transaction
            StockTransaction tx = StockTransaction.builder()
                    .inventoryItem(inventoryItem)
                    .stockLot(stockLot)
                    .transactionType(StockTransactionType.DISPENSE)
                    .quantity(dto.getQuantityDispensed())
                    .reason("Dispense for prescription " + prescription.getId())
                    .performedByUser(dispensedByUser)
                    .build();
            stockTransactionRepository.save(tx);
        }

        // Build and save the dispense record
        DispenseMapper.DispenseContext ctx = new DispenseMapper.DispenseContext(
                prescription, patient, pharmacy, stockLot,
                dispensedByUser, verifiedByUser, catalogItem);
        Dispense dispense = dispenseMapper.toEntity(dto, ctx);
        dispense.setDispensedAt(LocalDateTime.now());
        Dispense saved = dispenseRepository.save(dispense);

        // Update prescription status based on dispensed quantity
        updatePrescriptionStatus(prescription, dto.getQuantityRequested(), dto.getQuantityDispensed());

        logAudit(AuditEventType.DISPENSE_CREATED,
                "Dispensed " + dto.getQuantityDispensed() + " " + (dto.getUnit() != null ? dto.getUnit() : "units")
                        + " of " + dto.getMedicationName() + " to patient " + patient.getId(),
                saved.getId().toString());

        return dispenseMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public DispenseResponseDTO getDispense(UUID id) {
        Dispense dispense = dispenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dispense record not found"));
        enforceHospitalScope(dispense.getPharmacy());
        return dispenseMapper.toResponseDTO(dispense);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DispenseResponseDTO> listByPrescription(UUID prescriptionId, Pageable pageable) {
        return dispenseRepository.findByPrescriptionId(prescriptionId, pageable)
                .map(dispenseMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DispenseResponseDTO> listByPatient(UUID patientId, Pageable pageable) {
        return dispenseRepository.findByPatientId(patientId, pageable)
                .map(dispenseMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DispenseResponseDTO> listByPharmacy(UUID pharmacyId, Pageable pageable) {
        Pharmacy pharmacy = pharmacyRepository.findById(pharmacyId)
                .orElseThrow(() -> new ResourceNotFoundException("Pharmacy not found"));
        enforceHospitalScope(pharmacy);
        return dispenseRepository.findByPharmacyId(pharmacyId, pageable)
                .map(dispenseMapper::toResponseDTO);
    }

    @Override
    @Transactional
    public DispenseResponseDTO cancelDispense(UUID id) {
        Dispense dispense = dispenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Dispense record not found"));
        enforceHospitalScope(dispense.getPharmacy());

        if (dispense.getStatus() == DispenseStatus.CANCELLED) {
            throw new BusinessException("Dispense is already cancelled");
        }
        if (dispense.getStatus() != DispenseStatus.COMPLETED && dispense.getStatus() != DispenseStatus.PARTIAL) {
            throw new BusinessException("Only completed or partial dispenses can be cancelled");
        }

        dispense.setStatus(DispenseStatus.CANCELLED);

        // Reverse stock if a lot was used
        if (dispense.getStockLot() != null) {
            StockLot lot = dispense.getStockLot();
            lot.setRemainingQuantity(lot.getRemainingQuantity().add(dispense.getQuantityDispensed()));
            stockLotRepository.save(lot);

            InventoryItem item = lot.getInventoryItem();
            item.setQuantityOnHand(item.getQuantityOnHand().add(dispense.getQuantityDispensed()));
            inventoryItemRepository.save(item);

            User performer = resolveCurrentUser();
            StockTransaction reverseTx = StockTransaction.builder()
                    .inventoryItem(item)
                    .stockLot(lot)
                    .transactionType(StockTransactionType.RETURN)
                    .quantity(dispense.getQuantityDispensed())
                    .reason("Dispense cancelled — stock returned for prescription "
                            + dispense.getPrescription().getId())
                    .performedByUser(performer)
                    .build();
            stockTransactionRepository.save(reverseTx);
        }

        Dispense saved = dispenseRepository.save(dispense);

        logAudit(AuditEventType.DISPENSE_CANCELLED,
                "Cancelled dispense of " + dispense.getQuantityDispensed() + " "
                        + dispense.getMedicationName(),
                saved.getId().toString());

        return dispenseMapper.toResponseDTO(saved);
    }

    // ── Private helpers ──

    private void updatePrescriptionStatus(Prescription prescription,
                                          BigDecimal quantityRequested, BigDecimal quantityDispensed) {
        if (quantityDispensed.compareTo(quantityRequested) >= 0) {
            prescription.setStatus(PrescriptionStatus.DISPENSED);
        } else {
            prescription.setStatus(PrescriptionStatus.PARTIALLY_FILLED);
        }
        prescriptionRepository.save(prescription);
    }

    private void enforceHospitalScope(Pharmacy pharmacy) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId != null && pharmacy.getHospital() != null
                && !pharmacy.getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException("Pharmacy not found");
        }
    }

    private User resolveCurrentUser() {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("Unable to determine current user");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Current user not found"));
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
                    .entityType("DISPENSE")
                    .build());
        } catch (Exception e) {
            log.warn("Failed to log audit event {}: {}", eventType, e.getMessage());
        }
    }
}
