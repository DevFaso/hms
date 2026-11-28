package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.InstrumentOutboxStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ChartRestrictedException;
import com.example.hms.security.chart.RestrictedChartGate;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.hl7.mllp.MllpOutboundProperties;
import com.example.hms.model.Hospital;
import com.example.hms.model.InstrumentOutbox;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.InstrumentOutboxPageDTO;
import com.example.hms.payload.dto.InstrumentOutboxResponseDTO;
import com.example.hms.payload.dto.InstrumentOutboxTransportDTO;
import com.example.hms.repository.InstrumentOutboxRepository;
import com.example.hms.utility.Hl7v2MessageBuilder;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstrumentOutboxServiceImplTest {

    @Mock private InstrumentOutboxRepository outboxRepository;
    @Mock private Hl7v2MessageBuilder hl7v2MessageBuilder;
    @Mock private RoleValidator roleValidator;
    @Mock private com.example.hms.repository.LabResultRepository labResultRepository;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private RestrictedChartGate restrictedChartGate;

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
        hospital.setName("Ordering hospital");
        labOrder = LabOrder.builder().build();
        labOrder.setId(labOrderId);
        labOrder.setHospital(hospital);
        com.example.hms.model.Patient patient = new com.example.hms.model.Patient();
        patient.setId(UUID.randomUUID());
        labOrder.setPatient(patient);
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
    void enqueueResultObservation_saveThrows_propagatesBecauseTheTransactionIsAlreadyPoisoned() {
        // The rollback-only argument is about the INSERT: it marks the
        // caller's transaction whatever anyone catches, so containing it
        // would hide the cause and hand the caller a 500 at commit anyway.
        LabResult result = LabResult.builder().labOrder(labOrder).resultValue("5.2").build();
        result.setId(java.util.UUID.randomUUID());
        when(hl7v2MessageBuilder.buildOruR01(result)).thenReturn("MSH|built-message");
        when(outboxRepository.save(any())).thenThrow(new RuntimeException("insert failed"));

        assertThatThrownBy(() -> service.enqueueResultObservation(result))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("insert failed");
    }

    @Test
    void enqueueResultObservation_builderThrows_costsTheMessageNotTheResult() {
        // It joins the caller's transaction, so a failure in here marks that
        // transaction rollback-only whatever this method does with it:
        // swallowing bought nothing and lied twice — the caller believed the
        // enqueue was contained and got a 500 at commit anyway, with the
        // cause logged as a warning rather than raised.
        LabResult result = LabResult.builder()
            .labOrder(labOrder)
            .resultValue("5.2")
            .build();
        result.setId(java.util.UUID.randomUUID());

        when(hl7v2MessageBuilder.buildOruR01(result))
            .thenThrow(new RuntimeException("HL7 build failed"));

        // Building the message is pure formatting and dereferences the
        // order's patient: an interface defect there must not block every
        // result entry on the order. It costs this one outbound message.
        assertThatCode(() -> service.enqueueResultObservation(result)).doesNotThrowAnyException();

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
        // QA D4078: a cause, not the stored English sentence (no bundle is loaded here).
        assertThat(errorRow.getLastError()).isEqualTo("NEGATIVE_ACK: MSA|AE|1");
        assertThat(errorRow.getAttempts()).isEqualTo(1);
    }

    @Test
    void getMessagesByLabOrder_performingLaboratorySeesItsOwnResultMessages() {
        Hospital performing = new Hospital();
        performing.setId(UUID.randomUUID());
        labOrder.setPerformingHospital(performing);
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(outboxRepository.findByLabOrder_Id(labOrderId))
            .thenReturn(List.of(message(InstrumentOutboxStatus.PENDING)));

        assertThat(service.getMessagesByLabOrder(labOrderId)).hasSize(1);
    }

    @Test
    void getMessage_performingLaboratoryReadsTheMessage() {
        Hospital performing = new Hospital();
        performing.setId(UUID.randomUUID());
        labOrder.setPerformingHospital(performing);
        InstrumentOutbox row = message(InstrumentOutboxStatus.ACK);
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));

        assertThat(service.getMessage(row.getId()).getId()).isEqualTo(row.getId());
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
        labOrder.getPatient().setFirstName("Awa");
        labOrder.getPatient().setLastName("Kaboré");
        labOrder.setLabTestDefinition(com.example.hms.model.LabTestDefinition.builder().name("Kaliémie").build());
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
        assertThat(page.getContent().get(0).getOrderingHospitalId()).isEqualTo(hospitalId);
        assertThat(page.getContent().get(0).getOrderingHospitalName()).isEqualTo("Ordering hospital");
        // QA r81 D4168: the row says which order, patient and test it carries.
        assertThat(page.getContent().get(0).getPatientName()).contains("Awa");
        assertThat(page.getContent().get(0).getTestName()).isEqualTo("Kaliémie");
        assertThat(page.getContent().get(0).getLabOrderCode()).isNotBlank();
        // QA D4078: the text of the exception never reaches the page.
        assertThat(page.getContent().get(0).getLastError())
            .isEqualTo("TIMEOUT").doesNotContain("Exception");
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
            // QA D1187: a key in the reader's language, the unknown value as its argument.
            .satisfies(e -> {
                assertThat(((BusinessException) e).getMessageKey()).isEqualTo("instrumentOutbox.status.unknown");
                assertThat(((BusinessException) e).getArgs()).containsExactly("BANANA");
            });
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
    void restrictedChartRefusesPayloadAndRetryBeforeMutation() {
        InstrumentOutbox row = message(InstrumentOutboxStatus.ERROR);
        UUID patientId = labOrder.getPatient().getId();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));
        doThrow(new ChartRestrictedException(patientId)).when(restrictedChartGate).requireReadable(patientId);

        assertThatThrownBy(() -> service.getMessage(row.getId())).isInstanceOf(ChartRestrictedException.class);
        assertThatThrownBy(() -> service.retry(row.getId())).isInstanceOf(ChartRestrictedException.class);
        verify(auditEventLogService, never()).logEvent(any());
        verify(outboxRepository, never()).save(any());
        assertThat(row.getStatus()).isEqualTo(InstrumentOutboxStatus.ERROR);
    }

    @Test
    void retryRequiresAHospitalBeforeLoadingOrChangingTheMessage() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        assertThatThrownBy(() -> service.retry(UUID.randomUUID()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Hospital context required");
        verify(outboxRepository, never()).findById(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void missingPatientIdentityCannotExposePayloadOrRetry() {
        InstrumentOutbox row = message(InstrumentOutboxStatus.ERROR);
        labOrder.setPatient(null);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.getMessage(row.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.retry(row.getId())).isInstanceOf(ResourceNotFoundException.class);
        verify(restrictedChartGate, never()).requireReadable(any());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void orphanMessageIsNotFoundBeforePayloadOrRetry() {
        InstrumentOutbox row = message(InstrumentOutboxStatus.ERROR);
        row.setLabOrder(null);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service.getMessage(row.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.retry(row.getId())).isInstanceOf(ResourceNotFoundException.class);
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void realRestrictedChartGateBlocksAdministratorUntilBreakGlass() {
        UUID patientId = labOrder.getPatient().getId();
        UUID actor = UUID.randomUUID();
        var patients = mock(com.example.hms.repository.PatientRepository.class);
        var registrations = mock(com.example.hms.repository.PatientHospitalRegistrationRepository.class);
        var sessions = mock(com.example.hms.repository.BreakGlassSessionRepository.class);
        var breakGlass = mock(com.example.hms.service.recordaccess.BreakGlassGate.class);
        var realGate = new RestrictedChartGate(patients, registrations, breakGlass, sessions,
            java.time.Clock.systemUTC());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "restrictedChartGate", realGate);
        var auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
            "administrator", "n/a", org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_SUPER_ADMIN"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder()
                .principalUserId(actor).activeHospitalId(hospitalId)
                .permittedHospitalIds(java.util.Set.of(hospitalId)).staffHospitalIds(java.util.Set.of(hospitalId)).build());
        try {
            InstrumentOutbox row = message(InstrumentOutboxStatus.ERROR);
            when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
            when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));
            when(patients.findChartRestrictedById(patientId)).thenReturn(Optional.of(true));
            when(registrations.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);
            when(breakGlass.liveSession(actor, patientId, hospitalId)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getMessage(row.getId())).isInstanceOf(ChartRestrictedException.class);
            assertThatThrownBy(() -> service.retry(row.getId())).isInstanceOf(ChartRestrictedException.class);
            verify(outboxRepository, never()).save(any());
            when(breakGlass.liveSession(actor, patientId, hospitalId))
                .thenReturn(Optional.of(new com.example.hms.model.BreakGlassSession()));
            assertThat(service.getMessage(row.getId()).getPayload()).isEqualTo(row.getPayload());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            com.example.hms.security.context.HospitalContextHolder.clear();
        }
    }

    @Test
    void getMessage_foreignHospitalReadsAsNotFound() {
        InstrumentOutbox row = message(InstrumentOutboxStatus.ACK);
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.getMessage(row.getId()))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(auditEventLogService, never()).logEvent(any());
    }

    // ── access: the payload is a PHI read (owner, 2026-11-19) ───────────────────

    @Test
    void getMessage_auditsEveryPayloadReadAsADataAccessOnThePatient() {
        com.example.hms.model.Patient patient = new com.example.hms.model.Patient();
        patient.setId(UUID.randomUUID());
        labOrder.setPatient(patient);
        InstrumentOutbox row = message(InstrumentOutboxStatus.ACK);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(outboxRepository.findById(row.getId())).thenReturn(Optional.of(row));

        service.getMessage(row.getId());

        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.DATA_ACCESS);
        assertThat(audit.getValue().getEntityType()).isEqualTo(InstrumentOutboxServiceImpl.AUDIT_ENTITY_TYPE);
        assertThat(audit.getValue().getResourceId()).isEqualTo(row.getId().toString());
        assertThat(audit.getValue().getPatientId()).isEqualTo(patient.getId());
        assertThat(audit.getValue().getHospitalId()).isEqualTo(hospitalId);
        // Ids and the message type only: never the payload or the patient's name.
        assertThat(audit.getValue().getEventDescription())
            .isEqualTo("Instrument outbox message " + row.getId() + " (OML^O21) viewed")
            .doesNotContain("MSH");
    }

    @Test
    void getMessage_refusesTheUnscopedSuperAdminView() {
        // requireActiveHospitalId() answers null for an unpinned super-admin;
        // isHandledBy(null) is true, so without this check that view read
        // every hospital's patient names.
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.getMessage(id))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Hospital context required");
        verify(outboxRepository, never()).findById(any());
        verify(auditEventLogService, never()).logEvent(any());
    }

    @Test
    void getMessagesByLabOrder_listsSummariesWithoutThePayload() {
        InstrumentOutbox row = message(InstrumentOutboxStatus.ACK);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(outboxRepository.findByLabOrder_Id(labOrderId)).thenReturn(List.of(row));

        assertThat(service.getMessagesByLabOrder(labOrderId)).singleElement()
            .satisfies(dto -> assertThat(dto.getPayload()).isNull());
    }

    @Test
    void rowsCarryTheObservationStatusSoPreliminaryAndFinalDoNotLookLikeDuplicates() {
        InstrumentOutbox preliminary = message(InstrumentOutboxStatus.ACK);
        preliminary.setMessageType("ORU^R01");
        preliminary.setPayload("MSH|^~\\&|HMS|H|L|L|20261119153000+0000||ORU^R01|1|P|2.5.1\r"
            + "OBX|1|NM|GLU^Glucose||5.6|mmol/L||N|||P|||20261119153000+0000\r");
        InstrumentOutbox order = message(InstrumentOutboxStatus.ACK);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(outboxRepository.findByLabOrder_Id(labOrderId)).thenReturn(List.of(preliminary, order));

        assertThat(service.getMessagesByLabOrder(labOrderId))
            .extracting(InstrumentOutboxResponseDTO::getResultStatus)
            .containsExactly("P", null);
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
        assertThat(dto.getPayload()).isNull();
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
            .hasMessageContaining("instrumentOutbox.retry.notInError");

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

    // ── the released ORU, enqueued by id in its own transaction ──────────

    @Test
    @DisplayName("enqueueReleasedObservation loads the row by id and enqueues its final form")
    void enqueueReleasedObservationLoadsById() {
        UUID labResultId = UUID.randomUUID();
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        LabResult released = LabResult.builder().labOrder(order).build();
        released.setId(labResultId);
        released.setReleased(true);
        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(released));
        when(hl7v2MessageBuilder.buildOruR01(released)).thenReturn("MSH|...|ORU^R01|...");

        service.enqueueReleasedObservation(labResultId);

        ArgumentCaptor<InstrumentOutbox> captor = ArgumentCaptor.forClass(InstrumentOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getMessageType()).isEqualTo("ORU^R01");
        assertThat(captor.getValue().getLabOrder()).isSameAs(order);
    }

    @Test
    @DisplayName("enqueueReleasedObservation swallows a vanished row rather than failing the caller")
    void enqueueReleasedObservationToleratesAMissingRow() {
        UUID labResultId = UUID.randomUUID();
        when(labResultRepository.findById(labResultId)).thenReturn(Optional.empty());

        service.enqueueReleasedObservation(labResultId);

        verify(outboxRepository, never()).save(any());
    }

    /**
     * Item 4 of #720's last round: the javadoc promises this method does not
     * swallow its own failures, because only the caller — outside the
     * REQUIRES_NEW proxy — can see the commit. It used to delegate to a method
     * whose whole body was a catch-all, so a pre-commit failure was swallowed,
     * the new transaction committed empty, the caller's handler never fired and
     * the final outbound message was lost without a trace.
     */
    @Test
    @DisplayName("enqueueReleasedObservation lets a failure reach the caller, rather than committing empty")
    void enqueueReleasedObservationDoesNotSwallow() {
        UUID labResultId = UUID.randomUUID();
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        LabResult released = LabResult.builder().labOrder(order).build();
        released.setId(labResultId);
        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(released));
        when(hl7v2MessageBuilder.buildOruR01(released))
            .thenThrow(new IllegalStateException("cannot build the message"));

        assertThatThrownBy(() -> service.enqueueReleasedObservation(labResultId))
            .isInstanceOf(IllegalStateException.class);
        verify(outboxRepository, never()).save(any());
    }

    /** The best-effort entry path keeps its own catch: a failed message must not fail the result write. */
    @Test
    @DisplayName("enqueueResultObservation still swallows, as its own contract says")
    void enqueueResultObservationStillSwallows() {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        LabResult result = LabResult.builder().labOrder(order).build();
        result.setId(UUID.randomUUID());
        when(hl7v2MessageBuilder.buildOruR01(result))
            .thenThrow(new IllegalStateException("cannot build the message"));

        assertThatCode(() -> service.enqueueResultObservation(result)).doesNotThrowAnyException();
        verify(outboxRepository, never()).save(any());
    }
}
