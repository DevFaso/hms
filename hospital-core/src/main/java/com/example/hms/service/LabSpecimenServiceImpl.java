package com.example.hms.service;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.enums.LabSpecimenStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabSpecimenMapper;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabSpecimen;
import com.example.hms.payload.dto.LabSpecimenRequestDTO;
import com.example.hms.payload.dto.LabSpecimenResponseDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.service.lab.LabOrderLifecycle;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LabSpecimenServiceImpl implements LabSpecimenService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final java.security.SecureRandom SECURE_RNG = new java.security.SecureRandom();
    private static final String SPECIMEN_NOT_FOUND = "labspecimen.notfound";

    private final LabSpecimenRepository labSpecimenRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabSpecimenMapper labSpecimenMapper;
    private final RoleValidator roleValidator;
    private final InstrumentOutboxService instrumentOutboxService;
    /** QA D962: tells the ordering clinician a specimen was rejected. */
    private final com.example.hms.service.lab.LabSpecimenRejectionNotifier rejectionNotifier;
    /** Review of #975: a result already recorded from the tube (even preliminary) bars its rejection. */
    private final com.example.hms.repository.LabResultRepository labResultRepository;

    /** The specimen.rejection_reason column width (V239). */
    static final int MAX_REJECTION_REASON = 500;

    /** Specimens the laboratory can still refuse: not yet analysed, not already refused. */
    private static final java.util.Set<LabSpecimenStatus> REJECTABLE = java.util.EnumSet.of(
        LabSpecimenStatus.COLLECTED, LabSpecimenStatus.IN_TRANSIT, LabSpecimenStatus.RECEIVED);

    /** Specimens still standing for the order: another one of these means no re-collection is needed. */
    private static final java.util.Set<LabSpecimenStatus> LIVE = java.util.EnumSet.of(
        LabSpecimenStatus.PENDING, LabSpecimenStatus.COLLECTED, LabSpecimenStatus.IN_TRANSIT,
        LabSpecimenStatus.RECEIVED, LabSpecimenStatus.PROCESSING);

    private static final java.time.Duration COLLECTION_SKEW = java.time.Duration.ofMinutes(5);

    @Override
    @Transactional
    public LabSpecimenResponseDTO createSpecimen(LabSpecimenRequestDTO request, Locale locale) {
        if (request.getLabOrderId() == null) {
            throw new BusinessException("lab.specimen.orderRequired");
        }
        LabOrder labOrder = labOrderRepository.findById(request.getLabOrderId())
            .orElseThrow(() -> new ResourceNotFoundException("laborder.notfound"));

        // Hospital scope check: the ordering hospital or the performing laboratory (B1)
        if (!labOrder.isHandledBy(roleValidator.requireActiveHospitalId())) {
            throw new ResourceNotFoundException("laborder.notfound");
        }
        // QA D820: an order voided as entered in error takes nothing further.
        if (labOrder.getStatus() == LabOrderStatus.ENTERED_IN_ERROR) {
            throw new BusinessException("lab.order.enteredInError");
        }
        // QA D1109: nor does an order already answered (a result exists) or
        // cancelled: the collection left a dangling tube on a released order.
        if (!com.example.hms.service.lab.LabOrderLifecycle.acceptsNewSpecimen(labOrder.getStatus())) {
            throw new BusinessException("lab.specimen.orderClosed");
        }

        // QA D637: a specimen cannot be collected before the test was ordered
        // (a few minutes of clock skew between devices are tolerated).
        LocalDateTime collectedAt = request.getCollectedAt() != null ? request.getCollectedAt() : LocalDateTime.now(ZoneId.systemDefault());
        LocalDateTime orderedAt = labOrder.getOrderDatetime();
        if (orderedAt != null && collectedAt.isBefore(orderedAt.minus(COLLECTION_SKEW))) {
            throw new BusinessException("lab.specimen.collectedBeforeOrder");
        }
        // QA D649: nor in the future - a collection 48 h ahead was accepted and
        // then blocked every result on the order (a result cannot predate it).
        if (collectedAt.isAfter(LocalDateTime.now(ZoneId.systemDefault()).plus(COLLECTION_SKEW))) {
            throw new BusinessException("lab.specimen.collectedInFuture");
        }

        String accessionNumber = generateAccessionNumber();
        String barcodeValue = "LAB-" + accessionNumber;

        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .accessionNumber(accessionNumber)
            .barcodeValue(barcodeValue)
            .specimenType(request.getSpecimenType())
            .currentLocation(request.getCurrentLocation())
            .collectedAt(collectedAt)
            .collectedById(roleValidator.getCurrentUserId())
            .status(LabSpecimenStatus.COLLECTED)
            .notes(request.getNotes())
            .build();

        LabSpecimen saved = labSpecimenRepository.save(specimen);
        // A collected specimen IS the order's COLLECTED state (B2): nothing
        // else records it, and the transition endpoint has no callers.
        advanceOrder(labOrder, LabOrderStatus.COLLECTED);
        return labSpecimenMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public LabSpecimenResponseDTO getSpecimenById(UUID specimenId, Locale locale) {
        LabSpecimen specimen = labSpecimenRepository.findById(specimenId)
            .orElseThrow(() -> new ResourceNotFoundException(SPECIMEN_NOT_FOUND, specimenId));

        if (!specimen.getLabOrder().isHandledBy(roleValidator.requireActiveHospitalId())) {
            throw new ResourceNotFoundException(SPECIMEN_NOT_FOUND, specimenId);
        }
        return labSpecimenMapper.toResponseDTO(specimen);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LabSpecimenResponseDTO> getSpecimensByLabOrder(UUID labOrderId, Locale locale) {
        LabOrder labOrder = labOrderRepository.findById(labOrderId)
            .orElseThrow(() -> new ResourceNotFoundException("laborder.notfound"));

        if (!labOrder.isHandledBy(roleValidator.requireActiveHospitalId())) {
            throw new ResourceNotFoundException("laborder.notfound");
        }
        return labSpecimenRepository.findByLabOrder_Id(labOrderId)
            .stream()
            .map(labSpecimenMapper::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional
    public LabSpecimenResponseDTO receiveSpecimen(UUID specimenId, Locale locale) {
        LabSpecimen specimen = labSpecimenRepository.findById(specimenId)
            .orElseThrow(() -> new ResourceNotFoundException(SPECIMEN_NOT_FOUND, specimenId));

        if (!specimen.getLabOrder().isHandledBy(roleValidator.requireActiveHospitalId())) {
            throw new ResourceNotFoundException(SPECIMEN_NOT_FOUND, specimenId);
        }
        // QA D820: a specimen collected before its order was voided is not
        // received either - the receipt would queue an analyzer work order
        // (OML^O21) for an order that no longer exists clinically.
        if (specimen.getLabOrder().getStatus() == LabOrderStatus.ENTERED_IN_ERROR) {
            throw new BusinessException("lab.order.enteredInError");
        }
        // QA D1109: nor on a cancelled order.
        if (!com.example.hms.service.lab.LabOrderLifecycle.acceptsSpecimenReceipt(specimen.getLabOrder().getStatus())) {
            throw new BusinessException("lab.specimen.orderCancelled");
        }

        if (specimen.getStatus() != LabSpecimenStatus.COLLECTED
                && specimen.getStatus() != LabSpecimenStatus.IN_TRANSIT) {
            throw new BusinessException("lab.specimen.notReceivable", specimen.getStatus().name());
        }
        // Review of #975: a rejection that committed after this specimen was
        // read is seen here, under the order lock, instead of being written
        // over with RECEIVED and a work order queued for a refused tube.
        requireUnchangedUnderLock(specimen);

        specimen.setReceivedAt(LocalDateTime.now());
        specimen.setReceivedById(roleValidator.getCurrentUserId());
        specimen.setStatus(LabSpecimenStatus.RECEIVED);
        LabSpecimen saved = labSpecimenRepository.save(specimen);
        advanceOrder(specimen.getLabOrder(), LabOrderStatus.RECEIVED);
        instrumentOutboxService.enqueueSpecimenReceived(saved);
        return labSpecimenMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional
    public LabSpecimenResponseDTO rejectSpecimen(UUID specimenId, String reason, Locale locale) {
        LabSpecimen specimen = labSpecimenRepository.findById(specimenId)
            .orElseThrow(() -> new ResourceNotFoundException(SPECIMEN_NOT_FOUND, specimenId));
        LabOrder labOrder = specimen.getLabOrder();
        if (!labOrder.isHandledBy(roleValidator.requireActiveHospitalId())) {
            throw new ResourceNotFoundException(SPECIMEN_NOT_FOUND, specimenId);
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException("lab.specimen.rejectReasonRequired");
        }
        if (trimmed.length() > MAX_REJECTION_REASON) {
            throw new BusinessException("lab.specimen.rejectReasonTooLong", MAX_REJECTION_REASON);
        }
        if (labOrder.getStatus() == LabOrderStatus.ENTERED_IN_ERROR) {
            throw new BusinessException("lab.order.enteredInError");
        }
        if (!REJECTABLE.contains(specimen.getStatus())) {
            throw new BusinessException("lab.specimen.notRejectable");
        }

        // The order row is locked first, as every status writer here does, and
        // the decision is made on the committed status read under that lock.
        // Review of #975: the specimen too - a receipt or a second rejection
        // that committed after it was read must not be written over.
        requireUnchangedUnderLock(specimen);
        LabOrderStatus committedStatus = labOrderRepository.findStatusById(labOrder.getId());
        if (committedStatus == LabOrderStatus.ENTERED_IN_ERROR) {
            throw new BusinessException("lab.order.enteredInError");
        }
        if (!LabOrderLifecycle.acceptsSpecimenRejection(committedStatus)) {
            throw new BusinessException("lab.specimen.rejectAfterResult");
        }
        // Review of #975: an analyser's preliminary result leaves the order
        // short of RESULTED, so the status alone would let the tube be refused
        // while its value stays on the order. Any result row bars it; the
        // result is corrected instead.
        if (labResultRepository.existsByLabOrder_Id(labOrder.getId())) {
            throw new BusinessException("lab.specimen.rejectAfterResult");
        }

        specimen.setStatus(LabSpecimenStatus.REJECTED);
        specimen.setRejectionReason(trimmed);
        specimen.setRejectedAt(LocalDateTime.now(ZoneId.systemDefault()));
        specimen.setRejectedById(roleValidator.getCurrentUserId());
        LabSpecimen saved = labSpecimenRepository.save(specimen);

        // Back to awaiting collection, unless another specimen still stands
        // for the order (a second tube drawn at the same time).
        boolean anotherLive = labSpecimenRepository.findByLabOrder_Id(labOrder.getId()).stream()
            .anyMatch(other -> !java.util.Objects.equals(other.getId(), saved.getId())
                && LIVE.contains(other.getStatus()));
        LabOrderStatus moveTo = anotherLive ? null : LabOrderLifecycle.statusAfterSpecimenRejected(committedStatus);
        if (moveTo != null && labOrderRepository.updateStatusFrom(labOrder.getId(), committedStatus, moveTo) == 0) {
            log.debug("Lab order {} moved from {} while its specimen was being rejected; status left alone",
                labOrder.getId(), committedStatus);
        }
        if (rejectionNotifier != null) {
            rejectionNotifier.specimenRejected(saved);
        }
        return labSpecimenMapper.toResponseDTO(saved);
    }

    /**
     * Move the order on, by statement rather than through the entity.
     *
     * <p>The same writer the result path uses, and for the same reason: an
     * order instance loaded for something else carries a snapshot that may
     * predate another transaction's change, and writing status through it
     * either flushes nothing or flushes the whole row back. The status the
     * decision is made on is read fresh, and the update applies only if it is
     * still that.
     */
    private void advanceOrder(LabOrder labOrder, LabOrderStatus target) {
        if (labOrder == null || labOrder.getId() == null) {
            return;
        }
        // The lock first, as the result-entry path does. Reading the status
        // unlocked and then compare-and-setting on it leaves a window: a
        // concurrent move makes the update match nothing, and the only thing
        // that happened was a debug line — the order stays short of where
        // this specimen event should have put it, and nothing revisits it.
        LabOrderStatus committedStatus = lockedStatusOf(labOrder.getId());
        // QA D820: the void takes this same lock, so a void that committed
        // after the caller's status check is seen here. Refusing rolls back
        // the specimen write, and the receipt's outbox row is never queued.
        if (committedStatus == LabOrderStatus.ENTERED_IN_ERROR) {
            throw new BusinessException("lab.order.enteredInError");
        }
        LabOrderStatus moveTo = LabOrderLifecycle.statusAfterForwardStep(committedStatus, target);
        if (moveTo == null) {
            return;
        }
        if (labOrderRepository.updateStatusFrom(labOrder.getId(), committedStatus, moveTo) == 0) {
            log.debug("Lab order {} moved from {} while its specimen was being recorded; status left alone",
                labOrder.getId(), committedStatus);
        }
    }

    // ── Accession number generation ───────────────────────────────────────────
    private String generateAccessionNumber() {
        String datePart = LocalDateTime.now().format(DATE_FMT);
        for (int attempt = 0; attempt < 10; attempt++) {
            // 5 random uppercase alphanumeric characters
            String suffix = generateRandomSuffix(5);
            String accession = "ACC-" + datePart + "-" + suffix;
            if (!labSpecimenRepository.existsByAccessionNumber(accession)) {
                return accession;
            }
        }
        throw new BusinessException("lab.specimen.accessionRetry");
    }

    private static String generateRandomSuffix(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        var sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(SECURE_RNG.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * The order's committed status, read under the order row lock (the lock is
     * the point of the first read; its result is not needed).
     */
    private LabOrderStatus lockedStatusOf(UUID labOrderId) {
        labOrderRepository.findWithLockById(labOrderId);
        return labOrderRepository.findStatusById(labOrderId);
    }

    /**
     * Takes the order lock (every specimen writer takes it) and refuses when
     * the specimen's committed status is no longer the one it was read with:
     * the entity is saved whole, so a stale copy would write a concurrent
     * receipt or rejection back out (review of #975).
     */
    private void requireUnchangedUnderLock(LabSpecimen specimen) {
        labOrderRepository.findWithLockById(specimen.getLabOrder().getId());
        LabSpecimenStatus committed = labSpecimenRepository.findStatusById(specimen.getId());
        if (committed != specimen.getStatus()) {
            throw new BusinessException("lab.specimen.changedConcurrently");
        }
    }

}
