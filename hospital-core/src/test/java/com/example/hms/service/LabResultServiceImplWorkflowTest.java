package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.payload.dto.CriticalValueReadBackRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabResultSignatureRequestDTO;
import com.example.hms.payload.dto.LabResultTrendPointDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LabResultServiceImplWorkflowTest {

    private static final String SIGNER_FULL_NAME = "Seema Signer";

    @Mock
    private LabResultRepository labResultRepository;
    @Mock
    private LabOrderRepository labOrderRepository;
    @Mock
    private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock
    private LabResultMapper labResultMapper;
    @Mock
    private RoleValidator roleValidator;
    @Mock
    private AuthService authService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private com.example.hms.service.InstrumentOutboxService instrumentOutboxService;

    // Declared even though this suite drives no reflex order: the service
    // notifies the performing laboratory from that path, and an undeclared
    // dependency is injected as null — a trap the next reflex test springs.
    @Mock private com.example.hms.service.lab.LabOrderRoutingNotifier routingNotifier;
    @Mock private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;
    @Mock private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;

    @InjectMocks
    private LabResultServiceImpl labResultService;

    private UUID hospitalId;
    private LabOrder labOrder;
    private UserRoleHospitalAssignment resultAssignment;
    private LabTestDefinition labTestDefinition;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("General Hospital");

        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Patty");
        patient.setLastName("Patient");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setPhoneNumberPrimary("555-1000");
        patient.setEmail("patty@example.org");

        labOrder = new LabOrder();
        labOrder.setId(UUID.randomUUID());
        labOrder.setHospital(hospital);
        labOrder.setPatient(patient);

        labTestDefinition = new LabTestDefinition();
        labTestDefinition.setId(UUID.randomUUID());
        labTestDefinition.setName("Complete Blood Count");
        labOrder.setLabTestDefinition(labTestDefinition);

        resultAssignment = new UserRoleHospitalAssignment();
        resultAssignment.setId(UUID.randomUUID());
        resultAssignment.setHospital(hospital);

        // The actor is pinned to the order's hospital. These tests used to
        // leave the scope unstubbed, so the mock answered null and every
        // guard read it as "super-admin, unscoped" - the very hole the
        // null-scope rule closes. Tests about another scope stub their own.
        org.mockito.Mockito.lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
    }

    @Test
    void pendingReleaseIsTheActiveHospitalsUnreleasedRows() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        LabResult unreleased = buildLabResult(UUID.randomUUID());
        unreleased.setReleased(false);
        LabResultResponseDTO mapped = LabResultResponseDTO.builder().id(unreleased.getId().toString()).build();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(labResultRepository.findPendingReleaseHandledBy(hospitalId, pageable))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(unreleased)));
        when(labResultMapper.toResponseDTO(unreleased)).thenReturn(mapped);

        assertThat(labResultService.getPendingRelease(pageable, Locale.US).getContent()).containsExactly(mapped);
    }

    @Test
    void pendingReleaseNeedsAHospitalScope() {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);

        assertThrows(com.example.hms.exception.BusinessException.class,
            () -> labResultService.getPendingRelease(pageable, Locale.US));
        verify(labResultRepository, never()).findPendingReleaseHandledBy(any(), any());
    }

    @Test
    void getLabResultByIdIncludesTrendHistory() {
        UUID labResultId = UUID.randomUUID();
        LabResult current = buildLabResult(labResultId);
        LabResult previous = buildLabResult(UUID.randomUUID());
        previous.setResultDate(current.getResultDate().minusDays(3));

        LabResultResponseDTO baseResponse = LabResultResponseDTO.builder()
            .id(labResultId.toString())
            .build();

        LabResultTrendPointDTO previousPoint = LabResultTrendPointDTO.builder()
            .labResultId(previous.getId().toString())
            .resultDate(previous.getResultDate())
            .build();

        LabResultTrendPointDTO currentPoint = LabResultTrendPointDTO.builder()
            .labResultId(current.getId().toString())
            .resultDate(current.getResultDate())
            .build();

        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(current));
        // A caller pinned to the order's hospital reads the trend through the
        // scoped finder; the unscoped one is a verified super-admin's only.
        when(labResultRepository.findTrendReadableAt(
                org.mockito.ArgumentMatchers.eq(labOrder.getPatient().getId()),
                org.mockito.ArgumentMatchers.eq(labTestDefinition.getId()),
                any(),
                org.mockito.ArgumentMatchers.eq(hospitalId),
                any())
        ).thenReturn(List.of(current, previous));
        when(labResultMapper.toResponseDTO(current)).thenReturn(baseResponse);
        when(labResultMapper.toTrendPointDTO(current)).thenReturn(currentPoint);
        when(labResultMapper.toTrendPointDTO(previous)).thenReturn(previousPoint);

        LabResultResponseDTO response = labResultService.getLabResultById(labResultId, Locale.US);

        assertThat(response.getTrendHistory()).containsExactly(previousPoint, currentPoint);
    }

    @Test
    void releaseLabResultSetsMetadataAndReturnsDto() {
        UUID labResultId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        LabResult labResult = buildLabResult(labResultId);

        User actor = new User();
        actor.setId(actorId);
        actor.setFirstName("Casey");
        actor.setLastName("Clinician");
        resultAssignment.setUser(actor);

        LabResultResponseDTO responseDTO = LabResultResponseDTO.builder()
            .id(labResultId.toString())
            .released(true)
            .releasedByFullName("Casey Clinician")
            .build();

        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(labResult));
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(true);
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(actorId, hospitalId))
            .thenReturn(Optional.of(resultAssignment));
        when(labResultRepository.save(labResult)).thenReturn(labResult);
        when(labResultMapper.toResponseDTO(labResult)).thenReturn(responseDTO);

        LabResultResponseDTO result = labResultService.releaseLabResult(labResultId, Locale.US);

        assertThat(result).isSameAs(responseDTO);
        assertThat(labResult.isReleased()).isTrue();
        assertThat(labResult.getReleasedAt()).isNotNull();
        assertThat(labResult.getReleasedByUserId()).isEqualTo(actorId);
        assertThat(labResult.getReleasedByDisplay()).isEqualTo("Casey Clinician");
        verify(labResultRepository).save(labResult);
    }

    private void givenAReleasableResult(LabResult labResult) {
        when(labResultRepository.findById(labResult.getId())).thenReturn(Optional.of(labResult));
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(roleValidator.isLabScientist(any(), any())).thenReturn(true);
        when(labResultMapper.toResponseDTO(labResult)).thenReturn(LabResultResponseDTO.builder().build());
    }

    /**
     * The ORU enqueued when the result was created said OBX-11 = P, because
     * that is what an unreleased result is. The release has to send the final
     * form or the receiver holds a preliminary for ever.
     *
     * <p>By id and after commit: an enqueue inside the release transaction is
     * inserted at commit, where its try/catch cannot catch anything, so an
     * outbox failure would roll the release back.
     */
    @Test
    void releaseLabResultEnqueuesTheFinalObservationByIdAfterCommit() {
        UUID labResultId = UUID.randomUUID();
        LabResult labResult = buildLabResult(labResultId);
        labResult.setReleased(false);
        givenAReleasableResult(labResult);

        // A real synchronization, or TransactionCallbacks runs the action inline
        // and this test passes just as happily with the deferral deleted.
        TransactionSynchronizationManager.initSynchronization();
        try {
            labResultService.releaseLabResult(labResultId, Locale.US);

            verify(instrumentOutboxService, never()).enqueueReleasedObservation(any());
            assertThat(labResult.isReleased())
                .as("the row is released before the message is owed, so OBX-11 goes out as F")
                .isTrue();

            commitRegisteredCallbacks();
            verify(instrumentOutboxService).enqueueReleasedObservation(labResultId);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        // Never the entity overload — that one runs inside the caller's transaction.
        verify(instrumentOutboxService, never()).enqueueResultObservation(any());
    }

    /**
     * An outbox failure must not reach the caller. The enqueue runs after the
     * release has committed, so an exception escaping the callback would answer
     * 500 for a release that DID happen — and the retry would hit the
     * already-released early return and never enqueue anything at all.
     */
    @Test
    void aFailingEnqueueDoesNotFailTheRelease() {
        UUID labResultId = UUID.randomUUID();
        LabResult labResult = buildLabResult(labResultId);
        labResult.setReleased(false);
        givenAReleasableResult(labResult);
        doThrow(new IllegalStateException("outbox insert failed at commit"))
            .when(instrumentOutboxService).enqueueReleasedObservation(labResultId);

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertDoesNotThrow(() -> labResultService.releaseLabResult(labResultId, Locale.US));
            assertDoesNotThrow(this::commitRegisteredCallbacks);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(labResult.isReleased()).isTrue();
        verify(instrumentOutboxService).enqueueReleasedObservation(labResultId);
    }

    /** Fires what the transaction manager fires on a successful commit. */
    private void commitRegisteredCallbacks() {
        List.copyOf(TransactionSynchronizationManager.getSynchronizations())
            .forEach(TransactionSynchronization::afterCommit);
    }

    /**
     * A result INGESTED from an analyzer never had a first ORU from us, so a
     * release must not transmit one: it would be unsolicited, and its OBR-2
     * would carry our internal order UUID rather than the accession number the
     * analyzer knows the order by. The signal is the ROW's provenance — asking
     * whether an ORU had gone out for the ORDER answered yes for an order that
     * also held a hand-entered result, and sent exactly that message.
     */
    @Test
    void releaseDoesNotTransmitForAResultIngestedFromAnAnalyzer() {
        UUID labResultId = UUID.randomUUID();
        LabResult labResult = buildLabResult(labResultId);
        labResult.setReleased(false);
        labResult.setSourceSendingApplication("SYSMEX");
        labResult.setSourceMessageControlId("MSG-1");
        givenAReleasableResult(labResult);

        labResultService.releaseLabResult(labResultId, Locale.US);

        assertThat(labResult.isReleased()).isTrue();
        verify(instrumentOutboxService, never()).enqueueReleasedObservation(any());
        verify(instrumentOutboxService, never()).enqueueResultObservation(any());
    }

    /** An analyzer that omits MSH-10 still leaves its sending application on the row. */
    @Test
    void releaseDoesNotTransmitForAnIngestedResultWithNoMessageControlId() {
        UUID labResultId = UUID.randomUUID();
        LabResult labResult = buildLabResult(labResultId);
        labResult.setReleased(false);
        labResult.setSourceSendingApplication("SYSMEX");
        givenAReleasableResult(labResult);

        labResultService.releaseLabResult(labResultId, Locale.US);

        verify(instrumentOutboxService, never()).enqueueReleasedObservation(any());
    }

    /**
     * The defect the order-granular guard had, with the sibling that caused it
     * actually present: one order carrying a hand-entered result WE announced
     * and an ingested one we did not. Releasing the ingested row must stay
     * silent. Without building the sibling this test was a copy of the one
     * above and could not have caught a return to asking about the order.
     */
    @Test
    void releasingAnIngestedRowOnAnOrderWeAlsoTransmittedForStaysSilent() {
        UUID ingestedId = UUID.randomUUID();
        LabResult ingested = buildLabResult(ingestedId);
        ingested.setReleased(false);
        ingested.setSourceSendingApplication("SYSMEX");
        ingested.setSourceMessageControlId("MSG-2");

        // The sibling: ours, already transmitted, sitting on the same order —
        // which is exactly what made an order-granular guard answer "yes".
        LabResult oursAlreadyTransmitted = buildLabResult(UUID.randomUUID());
        oursAlreadyTransmitted.setReleased(true);
        assertThat(oursAlreadyTransmitted.getSourceSendingApplication())
            .as("the sibling is ours: no analyzer marks at all")
            .isNull();
        when(labResultRepository.findByLabOrder_Id(labOrder.getId()))
            .thenReturn(List.of(oursAlreadyTransmitted, ingested));

        givenAReleasableResult(ingested);

        labResultService.releaseLabResult(ingestedId, Locale.US);

        verify(instrumentOutboxService, never()).enqueueReleasedObservation(any());
        verify(instrumentOutboxService, never()).enqueueResultObservation(any());
    }

    @Test
    void releaseLabResultSkipsUpdateWhenAlreadyReleased() {
        UUID labResultId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        LabResult labResult = buildLabResult(labResultId);
        labResult.setReleased(true);
        labResult.setReleasedAt(LocalDateTime.now().minusHours(2));
        labResult.setReleasedByUserId(actorId);
        labResult.setReleasedByDisplay("Existing Actor");

        LabResultResponseDTO responseDTO = LabResultResponseDTO.builder()
            .id(labResultId.toString())
            .released(true)
            .releasedByFullName("Existing Actor")
            .build();

        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(labResult));
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(true);
        when(labResultMapper.toResponseDTO(labResult)).thenReturn(responseDTO);

        LabResultResponseDTO result = labResultService.releaseLabResult(labResultId, Locale.US);

        assertThat(result).isSameAs(responseDTO);
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    @Test
    void releaseLabResultRequiresPermission() {
        UUID labResultId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        LabResult labResult = buildLabResult(labResultId);

        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(labResult));
        when(authService.getCurrentUserId()).thenReturn(actorId);
        // B10: release is LabResultAuthority.RELEASE_ROLES at this hospital —
        // scientist, manager, director. Doctors, nurses, midwives and hospital
        // admins are no longer consulted at all.
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(false);
        when(roleValidator.isLabManager(actorId, hospitalId)).thenReturn(false);
        when(roleValidator.hasRole(actorId, hospitalId, "ROLE_LAB_DIRECTOR")).thenReturn(false);
        when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(false);

        assertThrows(BusinessException.class, () -> labResultService.releaseLabResult(labResultId, Locale.US));
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    @Test
    void signLabResultRecordsSignatureAndAcknowledgement() {
        UUID labResultId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        LabResult labResult = buildLabResult(labResultId);

        User actor = new User();
        actor.setId(actorId);
        actor.setFirstName("Seema");
        actor.setLastName("Signer");
        resultAssignment.setUser(actor);

        LabResultSignatureRequestDTO request = LabResultSignatureRequestDTO.builder()
            .signature("  SignedBySeema  ")
            .notes(" Reviewed and approved ")
            .build();

        LabResultResponseDTO responseDTO = LabResultResponseDTO.builder()
            .id(labResultId.toString())
            .signedBy(SIGNER_FULL_NAME)
            .signatureValue("SignedBySeema")
            .signatureNotes("Reviewed and approved")
            .acknowledged(true)
            .build();

        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(labResult));
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isDoctor(actorId, hospitalId)).thenReturn(true);
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(actorId, hospitalId))
            .thenReturn(Optional.of(resultAssignment));
        when(labResultRepository.save(labResult)).thenReturn(labResult);
        when(labResultMapper.toResponseDTO(labResult)).thenReturn(responseDTO);

        LabResultResponseDTO result = labResultService.signLabResult(labResultId, request, Locale.US);

        assertThat(result).isSameAs(responseDTO);
        assertThat(labResult.getSignedAt()).isNotNull();
        assertThat(labResult.getSignedByUserId()).isEqualTo(actorId);
        assertThat(labResult.getSignedByDisplay()).isEqualTo(SIGNER_FULL_NAME);
        assertThat(labResult.getSignatureValue()).isEqualTo("SignedBySeema");
        assertThat(labResult.getSignatureNotes()).isEqualTo("Reviewed and approved");
        assertThat(labResult.isAcknowledged()).isTrue();
        assertThat(labResult.getAcknowledgedByUserId()).isEqualTo(actorId);
        assertThat(labResult.getAcknowledgedByDisplay()).isEqualTo(SIGNER_FULL_NAME);
        verify(labResultRepository).save(labResult);
    }

    @Test
    void signLabResultRequiresPermission() {
        UUID labResultId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        LabResult labResult = buildLabResult(labResultId);

        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(labResult));
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isDoctor(actorId, hospitalId)).thenReturn(false);
        when(roleValidator.isMidwife(actorId, hospitalId)).thenReturn(false);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(false);
        when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(false);

        LabResultSignatureRequestDTO request = new LabResultSignatureRequestDTO();
        assertThrows(BusinessException.class,
            () -> labResultService.signLabResult(labResultId, request, Locale.US));
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    @Test
    void releaseLabResultThrowsWhenResultMissing() {
        UUID labResultId = UUID.randomUUID();
        when(labResultRepository.findById(labResultId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
            () -> labResultService.releaseLabResult(labResultId, Locale.US));
    }

    @Test
    void acknowledgeLabResultThrowsWhenResultMissing() {
        // Unknown ids were previously swallowed to keep the synthetic
        // pending-review rows acknowledgeable; that endpoint is gone, so a
        // missing result must surface as a 404 again.
        UUID labResultId = UUID.randomUUID();
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(labResultRepository.findById(labResultId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
            () -> labResultService.acknowledgeLabResult(labResultId, Locale.US));
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    @Test
    void signingACriticalResultDoesNotSilenceTheEscalation() {
        // The lab signing its own result is not the ordering clinician
        // confirming receipt. Sign used to auto-acknowledge, which bypassed
        // the read-back requirement for exactly the results it protects.
        UUID labResultId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        LabOrder order = new LabOrder();
        order.setHospital(hospital);
        LabResult critical = new LabResult();
        critical.setId(labResultId);
        critical.setLabOrder(order);
        critical.setCriticalNotifiedAt(java.time.LocalDateTime.now().minusMinutes(5));

        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(critical));
        when(authService.getCurrentUserId()).thenReturn(actorId);
        // Super-admin path sidesteps hospital-context resolution — the guard
        // under test is about acknowledgement, not signing permissions.
        when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(true);
        // A verified super-admin in global view: no hospital pin, and the
        // JWT flag that makes a null scope unscoped.
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        org.mockito.Mockito.lenient().when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(i -> i.getArgument(0));
        when(labResultMapper.toResponseDTO(any(LabResult.class)))
            .thenReturn(LabResultResponseDTO.builder().id(labResultId.toString()).build());

        LabResultSignatureRequestDTO request = LabResultSignatureRequestDTO.builder()
            .signature("signed")
            .build();
        labResultService.signLabResult(labResultId, request, Locale.US);

        assertThat(critical.getSignedAt()).isNotNull();
        assertThat(critical.isAcknowledged()).isFalse();
        assertThat(critical.getAcknowledgedAt()).isNull();
    }

    @Test
    void acknowledgeLabResultRefusesACriticalResultWithoutReadBack() {
        // The escalation sweep exits on acknowledged=false, so a bare
        // acknowledge on a critical result silences the whole safety chain with
        // nothing recording what the clinician was told. The read-back path is
        // the only way to acknowledge a result the notifier flagged critical.
        UUID labResultId = UUID.randomUUID();
        LabResult critical = new LabResult();
        critical.setId(labResultId);
        critical.setCriticalNotifiedAt(java.time.LocalDateTime.now().minusMinutes(5));

        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(critical));

        assertThrows(BusinessException.class,
            () -> labResultService.acknowledgeLabResult(labResultId, Locale.US));
        assertThat(critical.isAcknowledged()).isFalse();
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    @Test
    void acknowledgeLabResultStampsAcknowledgementFields() {
        UUID labResultId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        LabResult labResult = buildLabResult(labResultId);
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(labResult));

        labResultService.acknowledgeLabResult(labResultId, Locale.US);

        assertThat(labResult.isAcknowledged()).isTrue();
        assertThat(labResult.getAcknowledgedAt()).isNotNull();
        assertThat(labResult.getAcknowledgedByUserId()).isEqualTo(actorId);
        verify(labResultRepository).save(labResult);
    }

    private LabResult buildLabResult(UUID labResultId) {
        LabResult labResult = new LabResult();
        labResult.setId(labResultId);
        labResult.setLabOrder(labOrder);
        labResult.setAssignment(resultAssignment);
        labResult.setResultValue("Pending");
        labResult.setResultUnit("mmol/L");
        labResult.setResultDate(LocalDateTime.now().minusDays(1));
        labResult.setNotes("Initial pending result");
        return labResult;
    }

    // ── cross-tenant guards on acknowledge + read-back ──────────────────
    // These two were the ONLY single-row paths in this class without the
    // 404-not-403 scope comparison: a foreign tenant could acknowledge —
    // and thereby silence — another hospital's critical result by UUID.

    @Test
    void acknowledgeLabResultFromAnotherHospitalReadsAsNotFound() {
        UUID labResultId = UUID.randomUUID();
        LabResult labResult = buildLabResult(labResultId);
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(labResult));
        // Caller's active scope is a DIFFERENT hospital than the result's.
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        assertThrows(ResourceNotFoundException.class,
            () -> labResultService.acknowledgeLabResult(labResultId, Locale.US));
        assertThat(labResult.isAcknowledged()).isFalse();
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    @Test
    void acknowledgeLabResultPassesForTheOwningHospital() {
        UUID labResultId = UUID.randomUUID();
        LabResult labResult = buildLabResult(labResultId);
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(labResult));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        labResultService.acknowledgeLabResult(labResultId, Locale.US);

        assertThat(labResult.isAcknowledged()).isTrue();
    }

    @Test
    void criticalReadBackFromAnotherHospitalReadsAsNotFound() {
        // Worse than acknowledge: the response DTO carries patient name +
        // value, so the old unscoped load was a cross-tenant PHI read too.
        UUID labResultId = UUID.randomUUID();
        LabResult labResult = buildLabResult(labResultId);
        when(labResultRepository.findById(labResultId)).thenReturn(Optional.of(labResult));
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        CriticalValueReadBackRequestDTO request = new CriticalValueReadBackRequestDTO();
        request.setRepeatedValue("7.2");

        assertThrows(ResourceNotFoundException.class,
            () -> labResultService.recordCriticalReadBack(labResultId, request, Locale.US));
    }
}
