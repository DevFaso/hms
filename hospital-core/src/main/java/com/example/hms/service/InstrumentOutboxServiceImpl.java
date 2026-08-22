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
import com.example.hms.utility.Hl7v2MessageBuilder;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
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
    @Transactional
    public void enqueueResultObservation(LabResult result) {
        try {
            String payload = hl7v2MessageBuilder.buildOruR01(result);
            InstrumentOutbox message = InstrumentOutbox.builder()
                .labOrder(result.getLabOrder())
                .messageType("ORU^R01")
                .payload(payload)
                .status(InstrumentOutboxStatus.PENDING)
                .build();
            outboxRepository.save(message);
            log.debug("Enqueued ORU^R01 for result {} / order {}",
                result.getId(), result.getLabOrder().getId());
        } catch (Exception ex) {
            log.error("Failed to enqueue ORU^R01 for result {}: {}", result.getId(), ex.getMessage(), ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<InstrumentOutboxResponseDTO> getMessagesByLabOrder(UUID labOrderId) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        return outboxRepository.findByLabOrder_Id(labOrderId).stream()
            .filter(m -> hospitalId == null || hospitalId.equals(m.getLabOrder().getHospital().getId()))
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
        if (hospitalId != null && !hospitalId.equals(message.getLabOrder().getHospital().getId())) {
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
