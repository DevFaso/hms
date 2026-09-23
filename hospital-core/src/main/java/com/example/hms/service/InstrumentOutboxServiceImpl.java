package com.example.hms.service;

import com.example.hms.enums.InstrumentOutboxStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.hl7.mllp.MllpOutboundProperties;
import com.example.hms.model.InstrumentOutbox;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import com.example.hms.payload.dto.InstrumentOutboxPageDTO;
import com.example.hms.payload.dto.InstrumentOutboxResponseDTO;
import com.example.hms.payload.dto.InstrumentOutboxTransportDTO;
import com.example.hms.repository.InstrumentOutboxRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.utility.Hl7v2MessageBuilder;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentOutboxServiceImpl implements InstrumentOutboxService {

    private static final int MAX_PAGE_SIZE = 100;

    private final InstrumentOutboxRepository outboxRepository;
    private final Hl7v2MessageBuilder hl7v2MessageBuilder;
    private final RoleValidator roleValidator;
    private final MllpOutboundProperties outboundProperties;
    /** Last, so an existing positional constructor call in a test only appends. */
    private final LabResultRepository labResultRepository;

    private static final String ORU_R01 = "ORU^R01";

    @Override
    @Transactional
    public void enqueueSpecimenReceived(LabSpecimen specimen) {
        try {
            String payload = hl7v2MessageBuilder.buildOml021(specimen);
            InstrumentOutbox message = InstrumentOutbox.builder()
                .labOrder(specimen.getLabOrder())
                .messageType("OML^O21")
                .payload(payload)
                .status(InstrumentOutboxStatus.PENDING)
                .build();
            outboxRepository.save(message);
            log.debug("Enqueued OML^O21 for specimen {} / order {}",
                specimen.getAccessionNumber(), specimen.getLabOrder().getId());
        } catch (Exception ex) {
            log.error("Failed to enqueue OML^O21 for specimen {}: {}", specimen.getId(), ex.getMessage(), ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasTransmittedObservation(UUID labOrderId) {
        return labOrderId != null
            && outboxRepository.existsByLabOrder_IdAndMessageType(labOrderId, ORU_R01);
    }

    /**
     * Runs in its own transaction, after the release has committed, so a
     * failure here cannot roll the release back — and cannot be rolled back BY
     * it either: the result really is released, and the message really is
     * owed. Takes the id rather than the entity because the caller's
     * persistence context is gone by the time this runs.
     *
     * <p><strong>This method does not catch its own failures, and must not.</strong>
     * The INSERT and its bean validation happen when this transaction commits,
     * which is after any {@code catch} inside the method body has gone out of
     * scope — the same commit-time blind spot that made the in-caller enqueue
     * a rollback trap. The only place that can see a commit failure here is the
     * caller, outside this proxy, which is where the handler lives.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueReleasedObservation(UUID labResultId) {
        if (labResultId == null) {
            return;
        }
        LabResult result = labResultRepository.findById(labResultId).orElse(null);
        if (result == null) {
            log.warn("Released ORU^R01 not enqueued — labResult {} no longer exists", labResultId);
            return;
        }
        enqueueResultObservation(result);
    }

    @Override
    @Transactional
    public void enqueueResultObservation(LabResult result) {
        // NOT wrapped in a catch, deliberately. This runs in the caller's
        // transaction — the outbox row and the result commit together or not
        // at all, which is the whole point of an outbox — and a persistence
        // failure in here marks that transaction rollback-only whatever this
        // method does with the exception. Swallowing it therefore bought
        // nothing and lied twice: the caller believed the write was contained
        // and then got a 500 at commit anyway, with the cause logged as a
        // warning instead of raised (the #553 lesson, in a new place).
        // Failing loudly means the caller sees the real error and retries the
        // whole clinical write, which is recoverable; a result on the chart
        // with no ORU behind it is not.
        // Building the message is pure formatting, and it dereferences the
        // order's patient and test definition: a null one is an interface
        // defect, not a reason to refuse the clinician's result. Uncontaining
        // the SAVE is what the rollback-only argument justifies — a failed
        // INSERT poisons this transaction whatever anyone catches — and that
        // argument says nothing about the formatting, which fails on its own
        // and leaves the transaction untouched. So the build is contained and
        // the save is not: a formatting bug costs this one outbound message,
        // logged loudly, instead of blocking every result entry on the order.
        String payload;
        try {
            payload = hl7v2MessageBuilder.buildOruR01(result);
        } catch (RuntimeException cannotFormat) {
            log.error("ORU^R01 could not be built for result {}; the result stands, the message is not queued: {}",
                result.getId(), cannotFormat.getMessage(), cannotFormat);
            return;
        }
        InstrumentOutbox message = InstrumentOutbox.builder()
            .labOrder(result.getLabOrder())
            .messageType(ORU_R01)
            .payload(payload)
            .status(InstrumentOutboxStatus.PENDING)
            .build();
        outboxRepository.save(message);
        log.debug("Enqueued ORU^R01 for result {} / order {}",
            result.getId(), result.getLabOrder().getId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<InstrumentOutboxResponseDTO> getMessagesByLabOrder(UUID labOrderId) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        return outboxRepository.findByLabOrder_Id(labOrderId).stream()
            .filter(m -> m.getLabOrder().isHandledBy(hospitalId))
            .map(this::toResponseDTO)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public InstrumentOutboxPageDTO search(String status, int page, int size) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        InstrumentOutboxStatus statusFilter = parseStatus(status);

        Page<InstrumentOutbox> result = outboxRepository.searchScoped(
            hospitalId, statusFilter,
            PageRequest.of(Math.max(0, page),
                Math.min(Math.max(1, size), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt")));

        long pending = 0;
        long error = 0;
        long ack = 0;
        for (Object[] row : outboxRepository.countByStatusScoped(hospitalId)) {
            InstrumentOutboxStatus rowStatus = (InstrumentOutboxStatus) row[0];
            long count = (Long) row[1];
            switch (rowStatus) {
                case PENDING -> pending = count;
                case ERROR -> error = count;
                case ACK -> ack = count;
                default -> { /* SENT is declared but never written; fold nothing */ }
            }
        }

        return InstrumentOutboxPageDTO.builder()
            .content(result.getContent().stream().map(this::toSummaryDTO).toList())
            .page(result.getNumber())
            .size(result.getSize())
            .totalElements(result.getTotalElements())
            .pendingCount(pending)
            .errorCount(error)
            .ackCount(ack)
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public InstrumentOutboxResponseDTO getMessage(UUID id) {
        return toResponseDTO(loadScoped(id));
    }

    @Override
    @Transactional
    public InstrumentOutboxResponseDTO retry(UUID id) {
        InstrumentOutbox message = loadScoped(id);
        if (message.getStatus() != InstrumentOutboxStatus.ERROR) {
            throw new BusinessException(
                "Only a message in ERROR can be retried; this one is " + message.getStatus() + ".");
        }
        // Reset the attempt counter rather than just flipping the status: the
        // sweep's findDispatchable requires attempts < maxAttempts, and a row
        // parked at the ceiling would be re-parked before ever reaching the
        // wire. lastError is left in place so the queue still shows WHY it
        // failed until the next attempt overwrites or clears it.
        message.setStatus(InstrumentOutboxStatus.PENDING);
        message.setAttempts(0);
        message.setLastAttemptAt(null);
        InstrumentOutbox saved = outboxRepository.save(message);
        log.info("Instrument outbox {} manually requeued", saved.getId());
        return toResponseDTO(saved);
    }

    @Override
    public InstrumentOutboxTransportDTO getTransportStatus() {
        return InstrumentOutboxTransportDTO.builder()
            .enabled(outboundProperties.isEnabled())
            .host(outboundProperties.getHost())
            .port(outboundProperties.getPort())
            .maxAttempts(outboundProperties.getMaxAttempts())
            .retryAfterSeconds(outboundProperties.getRetryAfterSeconds())
            .batchSize(outboundProperties.getBatchSize())
            .build();
    }

    /**
     * Scoped load with the house 404 idiom: a message belonging to another
     * hospital is indistinguishable from one that does not exist.
     */
    private InstrumentOutbox loadScoped(UUID id) {
        InstrumentOutbox message = outboxRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Outbox message not found with ID: " + id));
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        // B1: the performing laboratory's own result messages are its to see and retry.
        if (!message.getLabOrder().isHandledBy(hospitalId)) {
            throw new ResourceNotFoundException("Outbox message not found with ID: " + id);
        }
        return message;
    }

    private InstrumentOutboxStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return InstrumentOutboxStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Unknown outbox status: " + status);
        }
    }

    private InstrumentOutboxResponseDTO toResponseDTO(InstrumentOutbox msg) {
        return withCommonFields(msg).payload(msg.getPayload()).build();
    }

    /** List rows travel without the payload — it is PHI-bearing and TEXT-sized. */
    private InstrumentOutboxResponseDTO toSummaryDTO(InstrumentOutbox msg) {
        return withCommonFields(msg).build();
    }

    private InstrumentOutboxResponseDTO.InstrumentOutboxResponseDTOBuilder withCommonFields(InstrumentOutbox msg) {
        return InstrumentOutboxResponseDTO.builder()
            .id(msg.getId())
            .labOrderId(msg.getLabOrder() != null ? msg.getLabOrder().getId() : null)
            .messageType(msg.getMessageType())
            .status(msg.getStatus() != null ? msg.getStatus().name() : null)
            .createdAt(msg.getCreatedAt())
            .sentAt(msg.getSentAt())
            .attempts(msg.getAttempts())
            .lastError(msg.getLastError())
            .lastAttemptAt(msg.getLastAttemptAt());
    }
}
