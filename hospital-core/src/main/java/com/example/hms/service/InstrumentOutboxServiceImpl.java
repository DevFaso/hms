package com.example.hms.service;

import com.example.hms.enums.InstrumentOutboxStatus;
import com.example.hms.model.InstrumentOutbox;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import com.example.hms.payload.dto.InstrumentOutboxResponseDTO;
import com.example.hms.repository.InstrumentOutboxRepository;
import com.example.hms.utility.Hl7v2MessageBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentOutboxServiceImpl implements InstrumentOutboxService {

    private final InstrumentOutboxRepository outboxRepository;
    private final Hl7v2MessageBuilder hl7v2MessageBuilder;

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
    public List<InstrumentOutboxResponseDTO> getPendingMessagesByLabOrder(UUID labOrderId) {
        return outboxRepository.findByLabOrder_Id(labOrderId).stream()
            .filter(m -> m.getStatus() == InstrumentOutboxStatus.PENDING)
            .map(this::toResponseDTO)
            .toList();
    }

    private InstrumentOutboxResponseDTO toResponseDTO(InstrumentOutbox msg) {
        return InstrumentOutboxResponseDTO.builder()
            .id(msg.getId())
            .labOrderId(msg.getLabOrder() != null ? msg.getLabOrder().getId() : null)
            .messageType(msg.getMessageType())
            .payload(msg.getPayload())
            .status(msg.getStatus() != null ? msg.getStatus().name() : null)
            .createdAt(msg.getCreatedAt())
            .sentAt(msg.getSentAt())
            .build();
    }
}
