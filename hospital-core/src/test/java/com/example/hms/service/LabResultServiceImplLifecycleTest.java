package com.example.hms.service;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.LabOrderChannel;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabReflexRule;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabReflexRuleRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.lab.LabResultEntryGuard;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The lab-order lifecycle as driven by results (B2), the auto-verification
 * switch (B5), the release authority (B10), the release tenancy guard (B11)
 * and reflex-order provenance (B12).
 */
@ExtendWith(MockitoExtension.class)
class LabResultServiceImplLifecycleTest {

    @Mock private LabResultRepository labResultRepository;
    @Mock private LabResultEntryGuard labResultEntryGuard;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private LabResultMapper labResultMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private AuthService authService;
    @Mock private UserRepository userRepository;
    @Mock private InstrumentOutboxService instrumentOutboxService;
    @Mock private LabReflexRuleRepository labReflexRuleRepository;
    @Mock private LabTestDefinitionRepository labTestDefinitionRepository;
    @Mock private CriticalValueNotificationService criticalValueNotificationService;

    @InjectMocks
    private LabResultServiceImpl service;

    private UUID hospitalId;
    private UUID actorId;
    private Hospital hospital;
    private LabOrder order;
    private LabTestDefinition testDefinition;
    private UserRoleHospitalAssignment assignment;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        actorId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());

        testDefinition = new LabTestDefinition();
        testDefinition.setId(UUID.randomUUID());
        testDefinition.setName("Potassium");
        testDefinition.setTestCode("K");

        order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setHospital(hospital);
        order.setPatient(patient);
        order.setLabTestDefinition(testDefinition);
        order.setStatus(LabOrderStatus.ORDERED);

        assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(hospital);
    }

    private LabResultRequestDTO entryRequest() {
        return LabResultRequestDTO.builder()
            .labOrderId(order.getId())
            .assignmentId(assignment.getId())
            .patientId(order.getPatient().getId())
            .resultValue("4.1")
            .resultUnit("mmol/L")
            .resultDate(LocalDateTime.now())
            .build();
    }

    private LabResult resultOn(LabOrder labOrder, boolean released) {
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(labOrder);
        result.setAssignment(assignment);
        result.setResultValue("4.1");
        result.setResultDate(LocalDateTime.now());
        result.setReleased(released);
        return result;
    }

    /** Everything createLabResult needs from its collaborators, for a lab scientist at the order's hospital. */
    private void stubEntryPath() {
        // Entry loads the order under the write lock (round 2): the
        // reopen/advance decision must see a concurrent release's COMPLETED.
        when(labOrderRepository.findWithLockById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.hasRole(actorId, hospitalId, "ROLE_LAB_SCIENTIST")).thenReturn(true);
        when(assignmentRepository.findById(assignment.getId())).thenReturn(Optional.of(assignment));
        when(labResultMapper.toEntity(any(), any(), any())).thenAnswer(inv -> resultOn(inv.getArgument(1), false));
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));
        // lenient: the out-of-range test overrides this with HIGH
        org.mockito.Mockito.lenient().when(labResultMapper.toResponseDTO(any(LabResult.class)))
            .thenReturn(LabResultResponseDTO.builder().severityFlag("NORMAL").build());
        when(labReflexRuleRepository.findByTriggerTestDefinition_IdAndActiveTrue(testDefinition.getId()))
            .thenReturn(List.of());
    }

    // ── B2: results drive the order ────────────────────────────────────────

    @Test
    @DisplayName("B2 — entering a result moves the order to RESULTED")
    void enteringAResultMovesTheOrderToResulted() {
        stubEntryPath();

        service.createLabResult(entryRequest(), Locale.ENGLISH);

        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.RESULTED);
        verify(labOrderRepository).save(order);
        // the status decision was made on the locked row, never on a plain read
        verify(labOrderRepository).findWithLockById(order.getId());
        verify(labOrderRepository, never()).findById(any());
    }

    @Test
    @DisplayName("B2 — a result entered on a COMPLETED order re-opens it to RESULTED")
    void enteringAResultReopensACompletedOrder() {
        // A correction or a late analyte after completion: the doctor must
        // see the order as having something new to review, and the release
        // path must run again for the new result.
        order.setStatus(LabOrderStatus.COMPLETED);
        stubEntryPath();

        service.createLabResult(entryRequest(), Locale.ENGLISH);

        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.RESULTED);
        verify(labOrderRepository, atLeastOnce()).save(order);
    }

    @Test
    @DisplayName("B2 — a result entered on a VERIFIED order re-opens it to RESULTED")
    void enteringAResultReopensAVerifiedOrder() {
        // VERIFIED counts as terminal for encounter closure, and advance()
        // cannot move it forward to RESULTED, so without the re-open the new
        // result sat unreleased on an order treated as done.
        order.setStatus(LabOrderStatus.VERIFIED);
        stubEntryPath();

        service.createLabResult(entryRequest(), Locale.ENGLISH);

        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.RESULTED);
        verify(labOrderRepository, atLeastOnce()).save(order);
    }

    @Test
    @DisplayName("B2 — completion locks the order row before counting released results")
    void completionLocksTheOrderRow() {
        // Under READ COMMITTED two concurrent releases of the last two results
        // each saw the other as unreleased; the write lock serialises them.
        // (The H2 IT cannot exercise the race; this pins that the lock is
        // requested on the release path.)
        order.setStatus(LabOrderStatus.RESULTED);
        LabResult last = resultOn(order, false);
        when(labResultRepository.findById(last.getId())).thenReturn(Optional.of(last));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(true);
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labOrderRepository.findWithLockById(order.getId())).thenReturn(Optional.of(order));
        when(labOrderRepository.findStatusById(order.getId())).thenReturn(LabOrderStatus.RESULTED);
        when(labResultRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of(last));
        when(labResultMapper.toResponseDTO(last)).thenReturn(LabResultResponseDTO.builder().build());

        service.releaseLabResult(last.getId(), Locale.ENGLISH);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(labOrderRepository, labResultRepository);
        inOrder.verify(labOrderRepository).findWithLockById(order.getId());
        inOrder.verify(labResultRepository).findByLabOrder_Id(order.getId());
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("B2 — releasing the last unreleased result completes the order")
    void releasingTheLastResultCompletesTheOrder() {
        order.setStatus(LabOrderStatus.RESULTED);
        LabResult first = resultOn(order, true);
        LabResult last = resultOn(order, false);
        when(labResultRepository.findById(last.getId())).thenReturn(Optional.of(last));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(true);
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labResultRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of(first, last));
        when(labResultMapper.toResponseDTO(last)).thenReturn(LabResultResponseDTO.builder().build());

        service.releaseLabResult(last.getId(), Locale.ENGLISH);

        assertThat(last.isReleased()).isTrue();
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.COMPLETED);
        verify(labOrderRepository).save(order);
    }

    @Test
    @DisplayName("a preliminary the final has superseded no longer holds the order open")
    void aSupersededPreliminaryDoesNotBlockCompletion() {
        // What an analyzer leaves behind: two rows for one analyte, the
        // preliminary unreleased for ever because nobody releases a value the
        // bench has already replaced. Counted as outstanding work it would
        // hold the order open for ever.
        order.setStatus(LabOrderStatus.RESULTED);
        LabResult preliminary = resultOn(order, false);
        preliminary.setTestCode("K");
        preliminary.setSourceSendingApplication("SYSMEX");
        preliminary.setObservationResultStatus("P");
        preliminary.setResultDate(LocalDateTime.now().minusMinutes(10));
        LabResult finalResult = resultOn(order, false);
        finalResult.setTestCode("K");
        finalResult.setSourceSendingApplication("SYSMEX");
        finalResult.setObservationResultStatus("F");
        when(labResultRepository.findById(finalResult.getId())).thenReturn(Optional.of(finalResult));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(true);
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labOrderRepository.findWithLockById(order.getId())).thenReturn(Optional.of(order));
        when(labOrderRepository.findStatusById(order.getId())).thenReturn(LabOrderStatus.RESULTED);
        when(labResultRepository.findByLabOrder_Id(order.getId()))
            .thenReturn(List.of(preliminary, finalResult));
        when(labResultMapper.toResponseDTO(finalResult)).thenReturn(LabResultResponseDTO.builder().build());

        service.releaseLabResult(finalResult.getId(), Locale.ENGLISH);

        assertThat(finalResult.isReleased()).isTrue();
        assertThat(preliminary.isReleased()).as("the record is untouched — only the reading changes").isFalse();
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("a pending result for a DIFFERENT analyte still holds the order open")
    void aPendingSiblingAnalyteStillBlocksCompletion() {
        order.setStatus(LabOrderStatus.RESULTED);
        LabResult pendingOtherAnalyte = resultOn(order, false);
        pendingOtherAnalyte.setTestCode("NA");
        pendingOtherAnalyte.setSourceSendingApplication("SYSMEX");
        pendingOtherAnalyte.setObservationResultStatus("P");
        LabResult finalResult = resultOn(order, false);
        finalResult.setTestCode("K");
        finalResult.setSourceSendingApplication("SYSMEX");
        finalResult.setObservationResultStatus("F");
        when(labResultRepository.findById(finalResult.getId())).thenReturn(Optional.of(finalResult));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(true);
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labOrderRepository.findWithLockById(order.getId())).thenReturn(Optional.of(order));
        when(labOrderRepository.findStatusById(order.getId())).thenReturn(LabOrderStatus.RESULTED);
        when(labResultRepository.findByLabOrder_Id(order.getId()))
            .thenReturn(List.of(pendingOtherAnalyte, finalResult));
        when(labResultMapper.toResponseDTO(finalResult)).thenReturn(LabResultResponseDTO.builder().build());

        service.releaseLabResult(finalResult.getId(), Locale.ENGLISH);

        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.RESULTED);
    }

    @Test
    @DisplayName("B2 — one unreleased result of a panel keeps the order open")
    void anUnreleasedSiblingKeepsTheOrderOpen() {
        order.setStatus(LabOrderStatus.RESULTED);
        LabResult released = resultOn(order, false);
        LabResult pending = resultOn(order, false);
        when(labResultRepository.findById(released.getId())).thenReturn(Optional.of(released));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(true);
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labOrderRepository.findWithLockById(order.getId())).thenReturn(Optional.of(order));
        when(labOrderRepository.findStatusById(order.getId())).thenReturn(LabOrderStatus.RESULTED);
        when(labResultRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of(released, pending));
        when(labResultMapper.toResponseDTO(released)).thenReturn(LabResultResponseDTO.builder().build());

        service.releaseLabResult(released.getId(), Locale.ENGLISH);

        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.RESULTED);
        verify(labOrderRepository, never()).save(any(LabOrder.class));
    }

    // ── B5: auto-verification is opt-in ────────────────────────────────────

    @Test
    @DisplayName("B5 — a normal result is NOT released on entry unless auto-verification is switched on")
    void normalResultStaysUnreleasedByDefault() {
        stubEntryPath();

        ArgumentCaptor<LabResult> saved = ArgumentCaptor.forClass(LabResult.class);
        service.createLabResult(entryRequest(), Locale.ENGLISH);

        verify(labResultRepository).save(saved.capture());
        assertThat(saved.getValue().isReleased()).isFalse();
        assertThat(saved.getValue().getReleasedByDisplay()).isNull();
        // and the order therefore stays RESULTED, not COMPLETED
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.RESULTED);
    }

    @Test
    @DisplayName("B5 — with the switch on, a normal result is auto-released and a single-result order completes")
    void autoVerificationReleasesNormalResultsWhenEnabled() {
        ReflectionTestUtils.setField(service, "autoVerificationEnabled", true);
        stubEntryPath();
        when(labOrderRepository.findWithLockById(order.getId())).thenReturn(Optional.of(order));
        when(labOrderRepository.findStatusById(order.getId())).thenReturn(LabOrderStatus.RESULTED);
        when(labResultRepository.findByLabOrder_Id(order.getId()))
            .thenAnswer(inv -> List.of(resultOn(order, true)));

        service.createLabResult(entryRequest(), Locale.ENGLISH);

        ArgumentCaptor<LabResult> saved = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anyMatch(r -> r.isReleased() && "Autoverification".equals(r.getReleasedByDisplay()));
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.COMPLETED);
    }

    @Test
    @DisplayName("B5 — even switched on, a manual result outside the reference range is not auto-released, and the critical notification still fires")
    void autoVerificationSkipsAnOutOfRangeManualResult() {
        // The REST path never sets abnormalFlag, so the flag alone said
        // "normal" for a potassium of 50: the mapper's reference-range
        // severity is the only signal a manually entered result carries.
        ReflectionTestUtils.setField(service, "autoVerificationEnabled", true);
        stubEntryPath();
        when(labResultMapper.toResponseDTO(any(LabResult.class)))
            .thenReturn(LabResultResponseDTO.builder().severityFlag("HIGH").build());

        service.createLabResult(entryRequest(), Locale.ENGLISH);

        ArgumentCaptor<LabResult> saved = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(saved.capture());
        assertThat(saved.getValue().isReleased()).isFalse();
        assertThat(saved.getValue().getAbnormalFlag()).isNull();
        verify(criticalValueNotificationService).notifyIfCritical(saved.getValue(), "HIGH");
        // not released, so no completion pass: the only locked load is the entry one
        verify(labOrderRepository, times(1)).findWithLockById(order.getId());
    }

    @Test
    @DisplayName("B5 — even switched on, an abnormal result is never auto-released")
    void autoVerificationSkipsAbnormalResults() {
        ReflectionTestUtils.setField(service, "autoVerificationEnabled", true);
        stubEntryPath();
        when(labResultMapper.toEntity(any(), any(), any())).thenAnswer(inv -> {
            LabResult r = resultOn(inv.getArgument(1), false);
            r.setAbnormalFlag(AbnormalFlag.CRITICAL);
            return r;
        });

        service.createLabResult(entryRequest(), Locale.ENGLISH);

        ArgumentCaptor<LabResult> saved = ArgumentCaptor.forClass(LabResult.class);
        verify(labResultRepository).save(saved.capture());
        assertThat(saved.getValue().isReleased()).isFalse();
    }

    // ── B10: release belongs to the laboratory ─────────────────────────────

    @Test
    @DisplayName("B8 — a super-admin may enter a result, as the edge matcher promises")
    void superAdminMayEnterAResult() {
        // SecurityConfig admits ROLE_SUPER_ADMIN to POST /lab-results, but
        // validateLabResultAuthor had no bypass, so a super-admin with no
        // per-hospital assignment got a 400 from their own endpoint.
        when(labOrderRepository.findWithLockById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(true);
        when(assignmentRepository.findById(assignment.getId())).thenReturn(Optional.of(assignment));
        when(labResultMapper.toEntity(any(), any(), any())).thenAnswer(inv -> resultOn(inv.getArgument(1), false));
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.lenient().when(labResultMapper.toResponseDTO(any(LabResult.class)))
            .thenReturn(LabResultResponseDTO.builder().severityFlag("NORMAL").build());
        when(labReflexRuleRepository.findByTriggerTestDefinition_IdAndActiveTrue(testDefinition.getId()))
            .thenReturn(List.of());

        service.createLabResult(entryRequest(), Locale.ENGLISH);

        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.RESULTED);
        verify(labResultRepository).save(any(LabResult.class));
        // the per-hospital role checks are never consulted for a super-admin
        verify(roleValidator, never()).isDoctor(any(), any());
    }

    @Test
    @DisplayName("re-releasing an already-released result completes an order a previous path left behind")
    void reReleasingRepairsAStrandedOrder() {
        // MLLP inbound releases results without touching the order, and
        // results released before this lifecycle existed left theirs at
        // RESULTED. The early return meant nothing could ever repair them.
        order.setStatus(LabOrderStatus.RESULTED);
        LabResult alreadyReleased = resultOn(order, true);
        when(labResultRepository.findById(alreadyReleased.getId())).thenReturn(Optional.of(alreadyReleased));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(true);
        when(labOrderRepository.findWithLockById(order.getId())).thenReturn(Optional.of(order));
        when(labOrderRepository.findStatusById(order.getId())).thenReturn(LabOrderStatus.RESULTED);
        when(labResultRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of(alreadyReleased));
        when(labResultMapper.toResponseDTO(alreadyReleased)).thenReturn(LabResultResponseDTO.builder().build());

        service.releaseLabResult(alreadyReleased.getId(), Locale.ENGLISH);

        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.COMPLETED);
        verify(labOrderRepository).save(order);
        // the result itself is untouched: its original release stands
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    @Test
    @DisplayName("a cancellation committed during the release wins over completion")
    void aConcurrentCancellationIsNotOverwritten() {
        // The locking finder returns the instance this persistence context
        // already holds, so its status can predate the lock. The committed
        // status is read under the lock: completing over somebody's
        // cancellation would erase their decision.
        order.setStatus(LabOrderStatus.RESULTED);
        LabResult last = resultOn(order, false);
        when(labResultRepository.findById(last.getId())).thenReturn(Optional.of(last));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(true);
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labOrderRepository.findWithLockById(order.getId())).thenReturn(Optional.of(order));
        when(labOrderRepository.findStatusById(order.getId())).thenReturn(LabOrderStatus.CANCELLED);
        when(labResultMapper.toResponseDTO(last)).thenReturn(LabResultResponseDTO.builder().build());

        service.releaseLabResult(last.getId(), Locale.ENGLISH);

        assertThat(last.isReleased()).isTrue();
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.CANCELLED);
        verify(labOrderRepository, never()).save(order);
        verify(labResultRepository, never()).findByLabOrder_Id(order.getId());
    }

    @Test
    @DisplayName("B10 — a doctor assigned at the hospital cannot release")
    void doctorCannotRelease() {
        LabResult result = resultOn(order, false);
        when(labResultRepository.findById(result.getId())).thenReturn(Optional.of(result));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(false);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(false);
        when(roleValidator.isLabManager(actorId, hospitalId)).thenReturn(false);
        when(roleValidator.hasRole(actorId, hospitalId, "ROLE_LAB_DIRECTOR")).thenReturn(false);
        // The old gate consulted these; they must no longer be enough.
        org.mockito.Mockito.lenient().when(roleValidator.isDoctor(actorId, hospitalId)).thenReturn(true);
        org.mockito.Mockito.lenient().when(roleValidator.isNurse(actorId, hospitalId)).thenReturn(true);
        org.mockito.Mockito.lenient().when(roleValidator.isHospitalAdmin(actorId, hospitalId)).thenReturn(true);

        UUID resultId = result.getId();
        assertThatThrownBy(() -> service.releaseLabResult(resultId, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
        assertThat(result.isReleased()).isFalse();
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    @Test
    @DisplayName("B10 — a lab director at the hospital releases")
    void labDirectorReleases() {
        LabResult result = resultOn(order, false);
        when(labResultRepository.findById(result.getId())).thenReturn(Optional.of(result));
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(roleValidator.isLabScientist(actorId, hospitalId)).thenReturn(false);
        when(roleValidator.isLabManager(actorId, hospitalId)).thenReturn(false);
        when(roleValidator.hasRole(actorId, hospitalId, "ROLE_LAB_DIRECTOR")).thenReturn(true);
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labOrderRepository.findWithLockById(order.getId())).thenReturn(Optional.of(order));
        when(labOrderRepository.findStatusById(order.getId())).thenReturn(LabOrderStatus.RESULTED);
        when(labResultRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of(result));
        when(labResultMapper.toResponseDTO(result)).thenReturn(LabResultResponseDTO.builder().build());

        service.releaseLabResult(result.getId(), Locale.ENGLISH);

        assertThat(result.isReleased()).isTrue();
        assertThat(result.getReleasedByUserId()).isEqualTo(actorId);
    }

    // ── B11: release and entry are tenant-scoped like every other path ─────

    @Test
    @DisplayName("B11 — releasing another hospital's result reads as 404, before any role check")
    void releaseFromAnotherHospitalReadsAsNotFound() {
        LabResult result = resultOn(order, false);
        when(labResultRepository.findById(result.getId())).thenReturn(Optional.of(result));
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        UUID resultId = result.getId();
        assertThatThrownBy(() -> service.releaseLabResult(resultId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(result.isReleased()).isFalse();
        verify(authService, never()).hasRole(any());
        verify(labResultRepository, never()).save(any(LabResult.class));
    }

    @Test
    @DisplayName("B11 — a super-admin pinned to another hospital is scoped too")
    void pinnedSuperAdminIsScopedOnRelease() {
        LabResult result = resultOn(order, false);
        when(labResultRepository.findById(result.getId())).thenReturn(Optional.of(result));
        // requireActiveHospitalId honours an explicit X-Hospital-Id for a
        // super-admin: that pin is a foreign hospital here.
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.lenient().when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(true);

        UUID resultId = result.getId();
        assertThatThrownBy(() -> service.releaseLabResult(resultId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("B11 — entering a result on another hospital's order reads as 404")
    void entryOnAnotherHospitalsOrderReadsAsNotFound() {
        when(labOrderRepository.findWithLockById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        LabResultRequestDTO request = entryRequest();
        assertThatThrownBy(() -> service.createLabResult(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(labResultRepository, never()).save(any(LabResult.class));
        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.ORDERED);
    }

    // ── B12: reflex children carry the parent's provenance ─────────────────

    @Test
    @DisplayName("B12 — a reflex child order carries the parent's diagnosis, NPI and documentation flag, never its signature")
    void reflexChildCopiesTheParentsMandatoryFields() {
        order.setPrimaryDiagnosisCode("E87.5");
        order.setAdditionalDiagnosisCodes(new java.util.ArrayList<>(List.of("N18.9")));
        order.setOrderingProviderNpi("1234567893");
        order.setProviderSignatureDigest("digest-of-the-attestation");
        order.setSignedAt(LocalDateTime.now().minusHours(1));
        order.setSignedByUserId(actorId);
        order.setDocumentationSharedWithLab(true);
        order.setDocumentationReference("DOC-42");
        order.setOrderChannel(LabOrderChannel.ELECTRONIC);

        LabTestDefinition reflexDef = new LabTestDefinition();
        reflexDef.setId(UUID.randomUUID());
        reflexDef.setTestCode("MG");
        LabReflexRule rule = new LabReflexRule();
        rule.setId(UUID.randomUUID());
        rule.setReflexTestDefinition(reflexDef);
        rule.setCondition("{\"severityFlag\":\"NORMAL\"}");

        stubEntryPath();
        when(labReflexRuleRepository.findByTriggerTestDefinition_IdAndActiveTrue(testDefinition.getId()))
            .thenReturn(List.of(rule));
        when(labTestDefinitionRepository.findById(reflexDef.getId())).thenReturn(Optional.of(reflexDef));
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createLabResult(entryRequest(), Locale.ENGLISH);

        ArgumentCaptor<LabOrder> saved = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        LabOrder child = saved.getAllValues().stream()
            .filter(o -> o != order)
            .findFirst()
            .orElseThrow();
        assertThat(child.getLabTestDefinition()).isSameAs(reflexDef);
        assertThat(child.getStatus()).isEqualTo(LabOrderStatus.ORDERED);
        assertThat(child.getPrimaryDiagnosisCode()).isEqualTo("E87.5");
        assertThat(child.getAdditionalDiagnosisCodes()).containsExactly("N18.9");
        assertThat(child.getOrderingProviderNpi()).isEqualTo("1234567893");
        // The provider attested to the parent test, not to the one the rule
        // ordered: no e-signature is fabricated onto the child.
        assertThat(child.getProviderSignatureDigest()).isNull();
        assertThat(child.getSignedAt()).isNull();
        assertThat(child.getSignedByUserId()).isNull();
        assertThat(child.isDocumentationSharedWithLab()).isTrue();
        assertThat(child.getDocumentationReference()).isEqualTo("DOC-42");
        assertThat(child.getOrderChannel()).isEqualTo(LabOrderChannel.ELECTRONIC);
    }
}
