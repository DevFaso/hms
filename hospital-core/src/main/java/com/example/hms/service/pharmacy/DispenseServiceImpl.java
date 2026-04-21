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
import com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO;
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
        UUID hospitalId = roleValidator.requireActiveHospitalId();

        // Validate quantities at the boundary (positive, dispensed <= requested)
        validateQuantities(dto.getQuantityRequested(), dto.getQuantityDispensed());

        Prescription prescription = loadAndValidatePrescription(dto, hospitalId);

        Patient patient = patientRepository.findById(dto.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("patient.notfound", dto.getPatientId()));

        Pharmacy pharmacy = pharmacyRepository.findById(dto.getPharmacyId())
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notfound"));
        enforceHospitalScope(pharmacy);

        ActorPair actors = resolveActors(dto);

        MedicationCatalogItem catalogItem = resolveCatalogItem(dto);

        StockLot stockLot = consumeStockLotIfPresent(dto, pharmacy, prescription, actors.dispensedBy());

        // Build and save the dispense record
        DispenseMapper.DispenseContext ctx = new DispenseMapper.DispenseContext(
                prescription, patient, pharmacy, stockLot,
                actors.dispensedBy(), actors.verifiedBy(), catalogItem);
        Dispense dispense = dispenseMapper.toEntity(dto, ctx);
        dispense.setDispensedAt(LocalDateTime.now());
        Dispense saved = dispenseRepository.save(dispense);

        // Update prescription status based on cumulative dispensed quantity (supports partial fills)
        updatePrescriptionStatusFromHistory(prescription);

        logAudit(AuditEventType.DISPENSE_CREATED,
                "Dispensed " + dto.getQuantityDispensed() + " " + (dto.getUnit() != null ? dto.getUnit() : "units")
                        + " of " + dto.getMedicationName() + " to patient " + patient.getId(),
                saved.getId().toString());

        return dispenseMapper.toResponseDTO(saved);
    }

    private Prescription loadAndValidatePrescription(DispenseRequestDTO dto, UUID hospitalId) {
        Prescription prescription = prescriptionRepository.findById(dto.getPrescriptionId())
                .orElseThrow(() -> new ResourceNotFoundException("prescription.notfound"));

        // Tenant isolation: prescription must belong to the active hospital
        if (prescription.getHospital() == null
                || !hospitalId.equals(prescription.getHospital().getId())) {
            throw new ResourceNotFoundException("prescription.notfound");
        }

        if (!DISPENSABLE_STATUSES.contains(prescription.getStatus())) {
            throw new BusinessException("Prescription is not in a dispensable state: " + prescription.getStatus());
        }

        // The patient must match the prescription's patient — do not trust DTO in isolation
        if (prescription.getPatient() == null
                || !prescription.getPatient().getId().equals(dto.getPatientId())) {
            throw new BusinessException("Patient does not match prescription");
        }
        return prescription;
    }

    private ActorPair resolveActors(DispenseRequestDTO dto) {
        // Actor identity comes from the authenticated principal, not the request body.
        UUID currentUserId = roleValidator.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException("Unable to determine current user");
        }

        // If the client supplies dispensedBy, it must match the authenticated user.
        // This prevents one staff member from recording a dispense under another's identity.
        if (dto.getDispensedBy() != null && !currentUserId.equals(dto.getDispensedBy())) {
            throw new BusinessException("dispensedBy must match the authenticated user");
        }

        User dispensedByUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));

        // verifiedBy (if present) must match the authenticated user
        User verifiedByUser = null;
        if (dto.getVerifiedBy() != null) {
            if (!currentUserId.equals(dto.getVerifiedBy())) {
                throw new BusinessException("verifiedBy must match the authenticated user");
            }
            verifiedByUser = dispensedByUser;
        }
        return new ActorPair(dispensedByUser, verifiedByUser);
    }

    private MedicationCatalogItem resolveCatalogItem(DispenseRequestDTO dto) {
        if (dto.getMedicationCatalogItemId() == null) {
            return null;
        }
        return medicationCatalogItemRepository.findById(dto.getMedicationCatalogItemId())
                .orElseThrow(() -> new ResourceNotFoundException("medication.catalog.notfound"));
    }

    private StockLot consumeStockLotIfPresent(DispenseRequestDTO dto, Pharmacy pharmacy,
                                              Prescription prescription, User performer) {
        if (dto.getStockLotId() == null) {
            return null;
        }
        StockLot stockLot = stockLotRepository.findById(dto.getStockLotId())
                .orElseThrow(() -> new ResourceNotFoundException("stocklot.notfound"));

        // The lot must belong to the target pharmacy (and therefore to the active hospital)
        InventoryItem inventoryItem = stockLot.getInventoryItem();
        if (inventoryItem == null
                || inventoryItem.getPharmacy() == null
                || !pharmacy.getId().equals(inventoryItem.getPharmacy().getId())) {
            throw new BusinessException("Stock lot does not belong to the selected pharmacy");
        }

        BigDecimal requested = dto.getQuantityDispensed();
        if (stockLot.getRemainingQuantity().compareTo(requested) < 0) {
            throw new BusinessException("Insufficient lot stock: "
                    + stockLot.getRemainingQuantity() + " remaining, requested " + requested);
        }

        stockLot.setRemainingQuantity(stockLot.getRemainingQuantity().subtract(requested));
        stockLotRepository.save(stockLot);

        if (inventoryItem.getQuantityOnHand().compareTo(requested) < 0) {
            throw new BusinessException("Insufficient inventory stock");
        }
        inventoryItem.setQuantityOnHand(inventoryItem.getQuantityOnHand().subtract(requested));
        inventoryItemRepository.save(inventoryItem);

        StockTransaction tx = StockTransaction.builder()
                .inventoryItem(inventoryItem)
                .stockLot(stockLot)
                .transactionType(StockTransactionType.DISPENSE)
                .quantity(requested)
                .reason("Dispense for prescription " + prescription.getId())
                .performedByUser(performer)
                .build();
        stockTransactionRepository.save(tx);
        return stockLot;
    }

    private record ActorPair(User dispensedBy, User verifiedBy) {}

    @Override
    @Transactional(readOnly = true)
    public DispenseResponseDTO getDispense(UUID id) {
        Dispense dispense = dispenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("dispense.notfound"));
        enforceHospitalScope(dispense.getPharmacy());
        return dispenseMapper.toResponseDTO(dispense);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DispenseResponseDTO> listByPrescription(UUID prescriptionId, Pageable pageable) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        // Validate the prescription belongs to the caller's hospital before returning any history
        Prescription prescription = prescriptionRepository.findById(prescriptionId)
                .orElseThrow(() -> new ResourceNotFoundException("prescription.notfound"));
        if (prescription.getHospital() == null
                || !hospitalId.equals(prescription.getHospital().getId())) {
            throw new ResourceNotFoundException("prescription.notfound");
        }
        return dispenseRepository.findByPrescriptionId(prescriptionId, pageable)
                .map(dispenseMapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DispenseResponseDTO> listByPatient(UUID patientId, Pageable pageable) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        // Filter out any dispense whose pharmacy is outside the caller's active hospital
        return dispenseRepository.findByPatientId(patientId, pageable)
                .map(d -> {
                    enforceHospitalScope(d.getPharmacy(), hospitalId);
                    return dispenseMapper.toResponseDTO(d);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DispenseResponseDTO> listByPharmacy(UUID pharmacyId, Pageable pageable) {
        Pharmacy pharmacy = pharmacyRepository.findById(pharmacyId)
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notfound"));
        enforceHospitalScope(pharmacy);
        return dispenseRepository.findByPharmacyId(pharmacyId, pageable)
                .map(dispenseMapper::toResponseDTO);
    }

    @Override
    @Transactional
    public DispenseResponseDTO cancelDispense(UUID id) {
        Dispense dispense = dispenseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("dispense.notfound"));
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

        // Recompute the prescription status from remaining non-cancelled dispenses
        updatePrescriptionStatusFromHistory(dispense.getPrescription());

        logAudit(AuditEventType.DISPENSE_CANCELLED,
                "Cancelled dispense of " + dispense.getQuantityDispensed() + " "
                        + dispense.getMedicationName(),
                saved.getId().toString());

        return dispenseMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<WorkQueuePrescriptionDTO> getWorkQueue(Pageable pageable) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        return prescriptionRepository
                .findByHospital_IdAndStatusIn(hospitalId,
                        java.util.List.copyOf(DISPENSABLE_STATUSES), pageable)
                .map(this::toWorkQueueDTO);
    }

    // ── Private helpers ──

    private void validateQuantities(BigDecimal requested, BigDecimal dispensed) {
        if (requested == null || requested.signum() <= 0) {
            throw new BusinessException("Quantity requested must be greater than zero");
        }
        if (dispensed == null || dispensed.signum() <= 0) {
            throw new BusinessException("Quantity dispensed must be greater than zero");
        }
        if (dispensed.compareTo(requested) > 0) {
            throw new BusinessException("Quantity dispensed cannot exceed quantity requested");
        }
    }

    private void updatePrescriptionStatusFromHistory(Prescription prescription) {
        BigDecimal requested = prescription.getQuantity() != null
                ? prescription.getQuantity() : BigDecimal.ZERO;
        BigDecimal dispensedToDate = dispenseRepository
                .sumQuantityDispensedForPrescription(prescription.getId(), DispenseStatus.CANCELLED);
        if (dispensedToDate == null) {
            dispensedToDate = BigDecimal.ZERO;
        }

        PrescriptionStatus nextStatus;
        if (dispensedToDate.signum() <= 0) {
            // All dispenses cancelled — return to a dispensable state
            nextStatus = PrescriptionStatus.SIGNED;
        } else if (requested.signum() > 0 && dispensedToDate.compareTo(requested) >= 0) {
            nextStatus = PrescriptionStatus.DISPENSED;
        } else {
            nextStatus = PrescriptionStatus.PARTIALLY_FILLED;
        }

        if (prescription.getStatus() != nextStatus) {
            prescription.setStatus(nextStatus);
            prescriptionRepository.save(prescription);
        }
    }

    private WorkQueuePrescriptionDTO toWorkQueueDTO(Prescription p) {
        WorkQueuePrescriptionDTO.Patient patient = null;
        if (p.getPatient() != null) {
            patient = WorkQueuePrescriptionDTO.Patient.builder()
                    .id(p.getPatient().getId())
                    .firstName(p.getPatient().getFirstName())
                    .lastName(p.getPatient().getLastName())
                    .build();
        }
        WorkQueuePrescriptionDTO.Staff staff = null;
        if (p.getStaff() != null) {
            WorkQueuePrescriptionDTO.StaffUser staffUser = null;
            if (p.getStaff().getUser() != null) {
                staffUser = WorkQueuePrescriptionDTO.StaffUser.builder()
                        .id(p.getStaff().getUser().getId())
                        .firstName(p.getStaff().getUser().getFirstName())
                        .lastName(p.getStaff().getUser().getLastName())
                        .build();
            }
            staff = WorkQueuePrescriptionDTO.Staff.builder()
                    .id(p.getStaff().getId())
                    .user(staffUser)
                    .build();
        }
        return WorkQueuePrescriptionDTO.builder()
                .id(p.getId())
                .medicationName(p.getMedicationName())
                .dosage(p.getDosage())
                .quantity(p.getQuantity())
                .quantityUnit(p.getQuantityUnit())
                .status(p.getStatus() != null ? p.getStatus().name() : null)
                .createdAt(p.getCreatedAt())
                .frequency(p.getFrequency())
                .patient(patient)
                .staff(staff)
                .build();
    }

    private void enforceHospitalScope(Pharmacy pharmacy) {
        enforceHospitalScope(pharmacy, roleValidator.requireActiveHospitalId());
    }

    private void enforceHospitalScope(Pharmacy pharmacy, UUID hospitalId) {
        if (hospitalId != null && pharmacy != null && pharmacy.getHospital() != null
                && !pharmacy.getHospital().getId().equals(hospitalId)) {
            throw new ResourceNotFoundException("pharmacy.notfound");
        }
    }

    private User resolveCurrentUser() {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("Unable to determine current user");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));
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
