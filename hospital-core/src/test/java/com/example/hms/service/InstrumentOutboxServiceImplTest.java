package com.example.hms.service;

import com.example.hms.enums.InstrumentOutboxStatus;
import com.example.hms.model.InstrumentOutbox;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import com.example.hms.payload.dto.InstrumentOutboxResponseDTO;
import com.example.hms.repository.InstrumentOutboxRepository;
import com.example.hms.utility.Hl7v2MessageBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstrumentOutboxServiceImplTest {

    @Mock private InstrumentOutboxRepository outboxRepository;
    @Mock private Hl7v2MessageBuilder hl7v2MessageBuilder;

    @InjectMocks
    private InstrumentOutboxServiceImpl service;

    private UUID labOrderId;
    private LabOrder labOrder;

    @BeforeEach
    void setUp() {
        labOrderId = UUID.randomUUID();
        labOrder   = LabOrder.builder().id(labOrderId).build();
    }

    // ── enqueueSpecimenReceived ───────────────────────────────────────────────

    @Test
    void enqueueSpecimenReceived_success_savesOml021Message() throws Exception {
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .accessionNumber("ACC-20240101-ABCDE")
            .build();

        when(hl7v2MessageBuilder.buildOml021(specimen)).thenReturn("MSH|...|OML^O21|...");

        service.enqueueSpecimenReceived(specimen);

        ArgumentCaptor<InstrumentOutbox> captor = ArgumentCaptor.forClass(InstrumentOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getMessageType()).isEqualTo("OML^O21");
        assertThat(captor.getValue().getPayload()).isEqualTo("MSH|...|OML^O21|...");
        assertThat(captor.getValue().getStatus()).isEqualTo(InstrumentOutboxStatus.PENDING);
        assertThat(captor.getValue().getLabOrder()).isEqualTo(labOrder);
    }

    @Test
    void enqueueSpecimenReceived_builderThrows_exceptionSwallowedNoSave() throws Exception {
        LabSpecimen specimen = LabSpecimen.builder()
            .labOrder(labOrder)
            .accessionNumber("ACC-20240101-XXXXX")
            .build();

        when(hl7v2MessageBuilder.buildOml021(specimen))
            .thenThrow(new RuntimeException("HL7 build failed"));

        // Should not propagate the exception
        assertThatCode(() -> service.enqueueSpecimenReceived(specimen))
            .doesNotThrowAnyException();

        verify(outboxRepository, never()).save(any());
    }

    // ── enqueueResultObservation ──────────────────────────────────────────────

    @Test
    void enqueueResultObservation_success_savesOruR01Message() throws Exception {
        LabResult result = LabResult.builder()
            .labOrder(labOrder)
            .resultValue("5.2")
            .build();

        when(hl7v2MessageBuilder.buildOruR01(result)).thenReturn("MSH|...|ORU^R01|...");

        service.enqueueResultObservation(result);

        ArgumentCaptor<InstrumentOutbox> captor = ArgumentCaptor.forClass(InstrumentOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getMessageType()).isEqualTo("ORU^R01");
        assertThat(captor.getValue().getPayload()).isEqualTo("MSH|...|ORU^R01|...");
        assertThat(captor.getValue().getStatus()).isEqualTo(InstrumentOutboxStatus.PENDING);
        assertThat(captor.getValue().getLabOrder()).isEqualTo(labOrder);
    }

    @Test
    void enqueueResultObservation_builderThrows_exceptionSwallowedNoSave() throws Exception {
        LabResult result = LabResult.builder()
            .labOrder(labOrder)
            .build();

        when(hl7v2MessageBuilder.buildOruR01(result))
            .thenThrow(new RuntimeException("HL7 build failed"));

        assertThatCode(() -> service.enqueueResultObservation(result))
            .doesNotThrowAnyException();

        verify(outboxRepository, never()).save(any());
    }

    // ── getPendingMessagesByLabOrder ──────────────────────────────────────────

    @Test
    void getPendingMessagesByLabOrder_returnsPendingOnly() {
        InstrumentOutbox pendingMsg = InstrumentOutbox.builder()
            .labOrder(labOrder)
            .messageType("OML^O21")
            .payload("MSH|...|OML^O21|...")
            .status(InstrumentOutboxStatus.PENDING)
            .build();
        InstrumentOutbox sentMsg = InstrumentOutbox.builder()
            .labOrder(labOrder)
            .messageType("ORU^R01")
            .payload("MSH|...|ORU^R01|...")
            .status(InstrumentOutboxStatus.SENT)
            .build();

        when(outboxRepository.findByLabOrder_Id(labOrderId))
            .thenReturn(List.of(pendingMsg, sentMsg));

        List<InstrumentOutboxResponseDTO> result = service.getPendingMessagesByLabOrder(labOrderId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMessageType()).isEqualTo("OML^O21");
        assertThat(result.get(0).getStatus()).isEqualTo("PENDING");
    }

    @Test
    void getPendingMessagesByLabOrder_noMessages_returnsEmpty() {
        when(outboxRepository.findByLabOrder_Id(labOrderId)).thenReturn(List.of());

        List<InstrumentOutboxResponseDTO> result = service.getPendingMessagesByLabOrder(labOrderId);

        assertThat(result).isEmpty();
    }

    @Test
    void getPendingMessagesByLabOrder_allSent_returnsEmpty() {
        InstrumentOutbox sentMsg = InstrumentOutbox.builder()
            .labOrder(labOrder)
            .messageType("OML^O21")
            .status(InstrumentOutboxStatus.SENT)
            .build();

        when(outboxRepository.findByLabOrder_Id(labOrderId)).thenReturn(List.of(sentMsg));

        List<InstrumentOutboxResponseDTO> result = service.getPendingMessagesByLabOrder(labOrderId);

        assertThat(result).isEmpty();
    }
}
