package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.RefillStatus;
import com.example.hms.enums.StockTransactionType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.DispenseMapper;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.RefillRequest;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockLot;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseResponseDTO;
import com.example.hms.payload.dto.pharmacy.WorkQueuePrescriptionDTO;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RefillRequestRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.repository.pharmacy.StockLotRepository;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final RefillRequestRepository refillRequestRepository;
    private final DispenseMapper dispenseMapper;
    private final RoleValidator roleValidator;
    private final PharmacyServiceSupport support;
    private final CdsCheckService cdsCheckService;

    /**
     * Roadmap row 4 / T-68 — self-proxy used by {@link #createDispense} so the
     * @Transactional persistence call traverses the Spring AOP proxy and the
     * race-recovery {@code findByIdempotencyKey} below it runs OUTSIDE the
     * rolled-back transaction. {@code @Lazy} is required to break the
     * chicken-and-egg between bean construction and self-injection.
     *
     * <p>Field injection (not constructor) is intentional: {@link
     * RequiredArgsConstructor} would force this field into the constructor
     * signature, which Spring cannot satisfy at construction time.
     *
     * <p>Stays {@code null} in pure-unit tests (no Spring container);
     * {@link #createDispense} falls back to a direct call in that case,
     * which is correct because tests never exercise the AOP transaction
     * machinery anyway.
     */
    @Lazy
    @Autowired
    private DispenseServiceImpl self;

    private static final String AUDIT_ENTITY = "DISPENSE";

    private static final Set<PrescriptionStatus> DISPENSABLE_STATUSES = Set.of(
            PrescriptionStatus.SIGNED,
            PrescriptionStatus.TRANSMITTED,
            PrescriptionStatus.PARTIALLY_FILLED
    );

    /**
     * Roadmap row 4 / T-68 — orchestrator that enforces idempotent replay
     * semantics around the transactional create body in
     * {@link #createDispenseTransactionally(DispenseRequestDTO)}.
     *
     * <p>Three paths:
     * <ol>
     *   <li><b>Pre-check fast path</b> — if the supplied idempotency key is
     *       already on file, return the existing DTO without touching the
     *       create transaction at all (no stock decrement, no audit, no SMS).</li>
     *   <li><b>Normal create</b> — delegate through the AOP proxy to the
     *       transactional inner method.</li>
     *   <li><b>Race recovery</b> — if two concurrent POSTs both pass the
     *       pre-check (each sees an empty lookup) and the second hits the
     *       V94 partial UNIQUE index ({@code uq_disp_idempotency_key}),
     *       Spring translates the constraint violation to
     *       {@link DataIntegrityViolationException}; the @Transactional
     *       proxy rolls back our stock decrement and bubbles the exception
     *       here. We re-look up by key — guaranteed to find the winning
     *       row at this point — and return that DTO. Copilot review on
     *       PR #287 caught the original race window.</li>
     * </ol>
     *
     * <p>Intentionally NOT @Transactional: the recovery {@code findByIdempotencyKey}
     * must execute in its own (auto-commit) read so it sees the winning
     * insert that committed in another transaction. Putting @Transactional
     * on this wrapper would put the lookup in the same rolled-back tx and
     * defeat the purpose.
     */
    @Override
    public DispenseResponseDTO createDispense(DispenseRequestDTO dto) {
        // Pre-check fast path — short-circuit a replayed POST from the
        // offline pharmacy queue BEFORE any side-effects fire. A blank/null
        // key falls through to the normal create path.
        String idempotencyKey = normalize(dto.getIdempotencyKey());
        if (idempotencyKey != null) {
            var replay = dispenseRepository.findByIdempotencyKey(idempotencyKey);
            if (replay.isPresent()) {
                log.info("[DISPENSE] idempotency replay hit — returning existing dispense id={} for key={}",
                        replay.get().getId(), idempotencyKey);
                return dispenseMapper.toResponseDTO(replay.get());
            }
        }

        // self may be null in pure-unit tests (no Spring container). Direct
        // call in that case — correctness equivalent because tests never
        // exercise the AOP transaction or the unique-index race.
        DispenseService delegate = self != null ? self : this;
        try {
            return delegate.createDispenseTransactionally(dto);
        } catch (DataIntegrityViolationException ex) {
            if (idempotencyKey == null) {
                // Some other unique-constraint hit (not idempotency); not
                // ours to recover from.
                throw ex;
            }
            // Race recovery — the @Transactional proxy already rolled back
            // our stock decrement; the winning insert from the racing tx is
            // now committed and visible.
            var winner = dispenseRepository.findByIdempotencyKey(idempotencyKey);
            if (winner.isPresent()) {
                log.info("[DISPENSE] idempotency race resolved — returning winner dispense id={} for key={}",
                        winner.get().getId(), idempotencyKey);
                return dispenseMapper.toResponseDTO(winner.get());
            }
            // Constraint violation but no winning row — should not happen
            // (the V94 index is the only constraint that could fire on this
            // path) but be defensive: surface the original exception so the
            // caller sees the real failure rather than a silent success.
            throw ex;
        }
    }

    /**
     * Transactional body of {@link #createDispense}. Public so the AOP proxy
     * can intercept; not invoked directly by callers — go through
     * {@link #createDispense} so idempotency + race recovery apply.
     */
    @Override
    @Transactional
    public DispenseResponseDTO createDispenseTransactionally(DispenseRequestDTO dto) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();

        // Validate quantities at the boundary (positive, dispensed <= requested)
        validateQuantities(dto.getQuantityRequested(), dto.getQuantityDispensed());

        Prescription prescription = loadAndValidatePrescription(dto, hospitalId);

        Patient patient = patientRepository.findById(dto.getPatientId())
                .orElseThrow(() -> new ResourceNotFoundException("patient.notfound", dto.getPatientId()));

        Pharmacy pharmacy = pharmacyRepository.findById(dto.getPharmacyId())
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notfound"));
        enforceHospitalScope(pharmacy);

        // P-08: prospective CDS check before any state mutates. CRITICAL severity
        // blocks the dispense unless the pharmacist supplied an override reason.
        // Defensive: treat a null result as a clear pass so a misconfigured/mocked
        // service never silently fails the dispense flow.
        com.example.hms.payload.dto.pharmacy.CdsAlertResult cdsResult =
                cdsCheckService.checkAtDispense(prescription, patient.getId());
        if (cdsResult == null) {
            cdsResult = com.example.hms.payload.dto.pharmacy.CdsAlertResult.clear();
        }
        if (cdsResult.requiresOverride()
                && (dto.getCdsOverrideReason() == null || dto.getCdsOverrideReason().isBlank())) {
            throw new BusinessException("CDS_CRITICAL: pharmacist override reason required — "
                    + String.join(" | ", cdsResult.alerts()));
        }

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

        // T-38: Ready-for-pickup SMS (French) — only when the Rx is now fully DISPENSED
        if (prescription.getStatus() == PrescriptionStatus.DISPENSED) {
            support.notifyReadyForPickup(patient, pharmacy, dto.getMedicationName());
        }

        logAudit(AuditEventType.DISPENSE_CREATED,
                "Dispensed " + dto.getQuantityDispensed() + " " + (dto.getUnit() != null ? dto.getUnit() : "units")
                        + " of " + dto.getMedicationName() + " to patient " + patient.getId(),
                saved.getId().toString());

        // P-04: emit a distinct audit event when the dispense is a substitution so that
        // formulary substitutions are queryable independently of regular dispenses.
        if (Boolean.TRUE.equals(dto.getSubstitution())) {
            String reason = dto.getSubstitutionReason() != null ? dto.getSubstitutionReason() : "(no reason provided)";
            logAudit(AuditEventType.DISPENSE_SUBSTITUTED,
                    "Substituted dispense for " + dto.getMedicationName() + " — reason: " + reason,
                    saved.getId().toString());
        }

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
        Page<Prescription> page = prescriptionRepository
                .findByHospital_IdAndStatusIn(hospitalId,
                        List.copyOf(DISPENSABLE_STATUSES), pageable);
        Map<UUID, RefillRequest> latestRefills = latestRefillsFor(page.getContent());
        return page.map(p -> toWorkQueueDTO(p, latestRefills.get(p.getId())));
    }

    /**
     * One query for the whole page rather than a lookup per row — the queue is
     * a hot pharmacy screen and this decoration is not worth an N+1.
     */
    private Map<UUID, RefillRequest> latestRefillsFor(List<Prescription> prescriptions) {
        if (prescriptions.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = prescriptions.stream().map(Prescription::getId).toList();
        Map<UUID, RefillRequest> latest = new HashMap<>();
        // Ordered newest-first by the query, so the first row per prescription wins.
        for (RefillRequest refill : refillRequestRepository.findByPrescription_IdInOrderByUpdatedAtDesc(ids)) {
            if (refill.getPrescription() != null) {
                latest.putIfAbsent(refill.getPrescription().getId(), refill);
            }
        }
        return latest;
    }

    // ── Private helpers ──

    /**
     * Roadmap row 4 / T-68 — trims and blank-coalesces the client-supplied
     * idempotency key. Returns {@code null} for any input that should NOT
     * participate in dedup (null, empty, whitespace-only). Mirrors the
     * mapper's blank-to-null contract so the lookup and the eventual
     * persisted column agree on what "no key" means.
     */
    private static String normalize(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        String trimmed = idempotencyKey.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

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
        BigDecimal expected = expectedLifetimeQuantity(prescription);
        BigDecimal dispensedToDate = dispenseRepository
                .sumQuantityDispensedForPrescription(prescription.getId(), DispenseStatus.CANCELLED);
        if (dispensedToDate == null) {
            dispensedToDate = BigDecimal.ZERO;
        }

        PrescriptionStatus nextStatus;
        if (dispensedToDate.signum() <= 0) {
            // All dispenses cancelled — return to a dispensable state
            nextStatus = PrescriptionStatus.SIGNED;
        } else if (expected.signum() > 0 && dispensedToDate.compareTo(expected) >= 0) {
            nextStatus = PrescriptionStatus.DISPENSED;
        } else {
            nextStatus = PrescriptionStatus.PARTIALLY_FILLED;
        }

        if (prescription.getStatus() != nextStatus) {
            prescription.setStatus(nextStatus);
            prescriptionRepository.save(prescription);
        }

        if (nextStatus == PrescriptionStatus.DISPENSED) {
            closeOutApprovedRefill(prescription);
        }
    }

    /**
     * Total quantity this prescription is entitled to across its whole life:
     * the prescribed quantity once for the original fill, plus once more for
     * every refill an approval has released.
     *
     * <p>The comparison used to be against {@code quantity} alone, which was
     * right only while a prescription could be filled once. Now that approving
     * a refill returns the prescription to the work queue, a lifetime sum is
     * already ≥ quantity before the refill fill even starts, so a partial
     * second fill would have reported as fully DISPENSED.
     */
    private BigDecimal expectedLifetimeQuantity(Prescription prescription) {
        BigDecimal perFill = prescription.getQuantity() != null
                ? prescription.getQuantity() : BigDecimal.ZERO;
        int refillsUsed = prescription.getRefillsUsed() != null ? prescription.getRefillsUsed() : 0;
        return perFill.multiply(BigDecimal.valueOf(1L + refillsUsed));
    }

    /**
     * Marks the refill request this fill satisfied as DISPENSED. The enum value
     * has existed since the feature shipped and nothing ever wrote it, so a
     * patient's refill history showed "Approved" forever — even after they had
     * collected the medication.
     *
     * <p>Best-effort: a bookkeeping miss here must never fail a dispense that
     * physically happened.
     */
    private void closeOutApprovedRefill(Prescription prescription) {
        try {
            refillRequestRepository
                    .findFirstByPrescription_IdAndStatusOrderByUpdatedAtDesc(
                            prescription.getId(), RefillStatus.APPROVED)
                    .ifPresent(refill -> {
                        refill.setStatus(RefillStatus.DISPENSED);
                        refillRequestRepository.save(refill);
                        log.info("Refill {} closed out as DISPENSED for prescription {}",
                                refill.getId(), prescription.getId());
                    });
        } catch (RuntimeException ex) {
            log.warn("Could not close out the approved refill for prescription {}: {}",
                    prescription.getId(), ex.getMessage());
        }
    }

    private WorkQueuePrescriptionDTO toWorkQueueDTO(Prescription p, RefillRequest latestRefill) {
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
                .refill(toRefillContext(p, latestRefill))
                .build();
    }

    /**
     * Null for a prescription that has never had a refill request AND carries no
     * refill allowance — the common first-fill case, whose payload is unchanged.
     */
    private WorkQueuePrescriptionDTO.Refill toRefillContext(Prescription p, RefillRequest latest) {
        boolean hasAllowance = p.getRefillsAllowed() != null
                || (p.getRefillsUsed() != null && p.getRefillsUsed() > 0);
        if (latest == null && !hasAllowance) {
            return null;
        }
        return WorkQueuePrescriptionDTO.Refill.builder()
                .allowed(p.getRefillsAllowed())
                .remaining(p.getRefillsRemaining())
                .used(p.getRefillsUsed())
                .lastStatus(latest != null && latest.getStatus() != null ? latest.getStatus().name() : null)
                .lastProviderNotes(latest != null ? latest.getProviderNotes() : null)
                .lastDecidedAt(latest != null ? latest.getUpdatedAt() : null)
                // An APPROVED request that has not yet been dispensed is precisely
                // "the patient is coming to collect a refill".
                .awaitingRefillPickup(latest != null && latest.getStatus() == RefillStatus.APPROVED)
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
        support.logAudit(eventType, description, resourceId, AUDIT_ENTITY);
    }
}
