package com.example.hms.service;

import com.example.hms.enums.InstrumentOutboxStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.hl7.mllp.MllpOutboundProperties;
import com.example.hms.model.Hospital;
import com.example.hms.model.InstrumentOutbox;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import com.example.hms.payload.dto.InstrumentOutboxPageDTO;
import com.example.hms.payload.dto.InstrumentOutboxResponseDTO;
import com.example.hms.payload.dto.InstrumentOutboxTransportDTO;
import com.example.hms.repository.InstrumentOutboxRepository;
import com.example.hms.utility.Hl7v2MessageBuilder;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstrumentOutboxServiceImplTest {

    @Mock private InstrumentOutboxRepository outboxRepository;
    @Mock private Hl7v2MessageBuilder hl7v2MessageBuilder;
    @Mock private RoleValidator roleValidator;

    // Real properties, not a mock: the transport endpoint must report the
    // actual defaults, and a mock would pass with all-zero nonsense.
    @Spy private MllpOutboundProperties outboundProperties = new MllpOutboundProperties();

    @InjectMocks
    private InstrumentOutboxServiceImpl service;

    private UUID labOrderId;
    private UUID hospitalId;
    private LabOrder labOrder;

    @BeforeEach
    void setUp() {
        labOrderId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        labOrder = LabOrder.builder().build();
        labOrder.setId(labOrderId);
        labOrder.setHospital(hospital);
    }

    private InstrumentOutbox message(InstrumentOutboxStatus status) {
        InstrumentOutbox msg = InstrumentOutbox.builder()
            .labOrder(labOrder)
            .messageType("OML^O21")
            .payload("MSH|...|OML^O21|...")
            .status(status)
            .build();
        msg.setId(UUID.randomUUID());
        return msg;
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
        assertThat(captor.getValue().getStatus()).isEqualTo(InstrumentOutboxStatus.PENDING);
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

    // ── getMessagesByLabOrder ─────────────────────────────────────────────────

    @Test
    void getMessagesByLabOrder_returnsEveryStatus_includingError() {
        // The pre-2026-08-22 behaviour filtered to PENDING, which hid exactly
        // the ERROR rows a monitoring read exists to show.
        InstrumentOutbox pending = message(InstrumentOutboxStatus.PENDING);
        InstrumentOutbox error = message(InstrumentOutboxStatus.ERROR);
        error.setLastError("Negative acknowledgement: MSA|AE|1");
        error.setAttempts(1);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(outboxRepository.findByLabOrder_Id(labOrderId)).thenReturn(List.of(pending, error));

        List<InstrumentOutboxResponseDTO> result = service.getMessagesByLabOrder(labOrderId);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(InstrumentOutboxResponseDTO::getStatus)
            .containsExactlyInAnyOrder("PENDING", "ERROR");
        InstrumentOutboxResponseDTO errorRow = result.stream()
            .filter(r -> "ERROR".equals(r.getStatus())).findFirst().orElseThrow();
        assertThat(errorRow.getLastError()).isEqualTo("Negative acknowledgement: MSA|AE|1");
        assertThat(errorRow.getAttempts()).isEqualTo(1);
    }

    @Test
    void getMessagesByLabOrder_scopedCallerCannotSeeAnotherHospitalsOrder() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        when(outboxRepository.findByLabOrder_Id(labOrderId))
            .thenReturn(List.of(message(InstrumentOutboxStatus.PENDING)));

        assertThat(service.getMessagesByLabOrder(labOrderId)).isEmpty();
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    void search_returnsScopedPageWithCountsAndNoPayload() {
        InstrumentOutbox row = message(InstrumentOutboxStatus.ERROR);
        row.setLastError("SocketTimeoutException: read timed out");
        row.setAttempts(5);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(outboxRepository.searchScoped(eq(hospitalId), eq(InstrumentOutboxStatus.ERROR), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 25), 1));
        when(outboxRepository.countByStatusScoped(hospitalId)).thenReturn(List.<Object[]>of(
            new Object[]{InstrumentOutboxStatus.PENDING, 3L},
            new Object[]{InstrumentOutboxStatus.ERROR, 2L},
            new Object[]{InstrumentOutboxStatus.ACK, 40L}));

        InstrumentOutboxPageDTO page = service.search("error", 0, 25);

        assertThat(page.getContent()).hasSize(1);
        // A list row must not ship the HL7 payload — PHI-bearing and TEXT-sized.
        assertThat(page.getContent().get(0).getPayload()).isNull();
        assertThat(page.getContent().get(0).getLastError()).contains("SocketTimeoutException");
        assertThat(page.getPendingCount()).isEqualTo(3);
        assertThat(page.getErrorCount()).isEqualTo(2);
        assertThat(page.getAckCount()).isEqualTo(40);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void search_refusesAnUnknownStatus() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.search("BANANA", 0, 25))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("BANANA");
    }

    // ── getMessage ────────────────────────────────────────────────────────────

    @Test
    void getMessage_includesTheFullPayload() {
        InstrumentOutbox row = message(InstrumentOutboxStatus.ACK);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));

        InstrumentOutboxResponseDTO dto = service.getMessage(row.getId());

        assertThat(dto.getPayload()).isEqualTo("MSH|...|OML^O21|...");
    }

    @Test
    void getMessage_foreignHospitalReadsAsNotFound() {
        InstrumentOutbox row = message(InstrumentOutboxStatus.ACK);
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.getMessage(row.getId()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── retry ─────────────────────────────────────────────────────────────────

    @Test
    void retry_requeuesAnErrorRowWithAFreshAttemptBudget() {
        InstrumentOutbox row = message(InstrumentOutboxStatus.ERROR);
        row.setAttempts(5);
        row.setLastError("Negative acknowledgement: MSA|AR|1");
        row.setLastAttemptAt(LocalDateTime.now().minusHours(2));

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(outboxRepository.save(any(InstrumentOutbox.class))).thenAnswer(inv -> inv.getArgument(0));

        InstrumentOutboxResponseDTO dto = service.retry(row.getId());

        assertThat(dto.getStatus()).isEqualTo("PENDING");
        // Attempts must reset: findDispatchable requires attempts < maxAttempts,
        // so a row requeued at the ceiling would be re-parked before reaching
        // the wire.
        assertThat(dto.getAttempts()).isZero();
        assertThat(dto.getLastAttemptAt()).isNull();
        // The failure reason stays visible until the next attempt rewrites it.
        assertThat(dto.getLastError()).contains("MSA|AR|1");
    }

    @Test
    void retry_refusesAnythingNotInError() {
        InstrumentOutbox row = message(InstrumentOutboxStatus.PENDING);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.retry(row.getId()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PENDING");

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void retry_foreignHospitalReadsAsNotFound() {
        InstrumentOutbox row = message(InstrumentOutboxStatus.ERROR);
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.retry(row.getId()))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(outboxRepository, never()).save(any());
    }

    // ── transport status ──────────────────────────────────────────────────────

    @Test
    void transportStatus_reportsTheRealConfiguration() {
        outboundProperties.setEnabled(true);
        outboundProperties.setHost("lis.example.org");
        outboundProperties.setPort(2575);

        InstrumentOutboxTransportDTO dto = service.getTransportStatus();

        assertThat(dto.isEnabled()).isTrue();
        assertThat(dto.getHost()).isEqualTo("lis.example.org");
        assertThat(dto.getPort()).isEqualTo(2575);
        assertThat(dto.getMaxAttempts()).isEqualTo(5);
        assertThat(dto.getRetryAfterSeconds()).isEqualTo(60);
        assertThat(dto.getBatchSize()).isEqualTo(50);
    }
}
