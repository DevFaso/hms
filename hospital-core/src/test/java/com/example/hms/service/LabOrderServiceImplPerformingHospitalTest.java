package com.example.hms.service;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.LabOrderChannel;
import com.example.hms.enums.LabOrderStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabOrderMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import com.example.hms.payload.dto.PerformingLabOptionDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.lab.LabOrderRoutingNotifier;
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Audit gap B1 on the order service: routing an order to a performing
 * laboratory, and the 404-not-403 scope predicate that admits the ordering
 * hospital and the performing hospital while a third hospital sees nothing.
 */
@ExtendWith(MockitoExtension.class)
class LabOrderServiceImplPerformingHospitalTest {

    @Mock private LabOrderRepository labOrderRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private LabTestDefinitionRepository labTestDefinitionRepository;
    @Mock private LabOrderMapper labOrderMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientHospitalRegistrationRepository patientHospitalRegistrationRepository;
    @Mock private RecordAccessPolicy recordAccessPolicy;
    @Mock private CrossHospitalReachRecorder reachRecorder;
    @Mock private LabOrderRoutingNotifier routingNotifier;
    @Mock private com.example.hms.repository.LabSpecimenRepository labSpecimenRepository;
    @Mock private com.example.hms.repository.LabResultRepository labResultRepository;

    @InjectMocks
    private LabOrderServiceImpl service;

    private Hospital ordering;
    private Hospital performing;
    private Hospital third;
    private Patient patient;
    private Staff staff;
    private UserRoleHospitalAssignment assignment;
    private LabTestDefinition definition;
    private LabOrder order;
    private final LabOrderResponseDTO mapped = LabOrderResponseDTO.builder().id("mapped").build();

    @BeforeEach
    void setUp() {
        ordering = hospital("Ordering Hospital", "ORD");
        performing = hospital("Central Laboratory", "LAB");
        third = hospital("Unrelated Clinic", "THR");

        patient = new Patient();
        patient.setId(UUID.randomUUID());

        User user = new User();
        user.setId(UUID.randomUUID());
        Role role = new Role();
        role.setCode("ROLE_DOCTOR");
        assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(ordering);
        assignment.setUser(user);
        assignment.setRole(role);
        staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(user);
        staff.setHospital(ordering);
        staff.setAssignment(assignment);
        staff.setJobTitle(JobTitle.DOCTOR);
        staff.setEmploymentType(EmploymentType.FULL_TIME);
        staff.setNpi("1234567890");

        definition = new LabTestDefinition();
        definition.setId(UUID.randomUUID());
        definition.setName("CBC");
        definition.setTestCode("CBC");

        order = LabOrder.builder()
            .patient(patient)
            .orderingStaff(staff)
            .assignment(assignment)
            .hospital(ordering)
            .labTestDefinition(definition)
            .status(LabOrderStatus.ORDERED)
            .orderDatetime(LocalDateTime.now())
            .clinicalIndication("Fatigue")
            .build();
        order.setId(UUID.randomUUID());
        order.setPerformingHospital(performing);
    }

    // ── ordering with a performing laboratory ─────────────────────────────

    @Test
    void createLabOrderStoresThePerformingHospitalAndNotifiesIt() {
        mockOrderLookups();
        when(hospitalRepository.findById(performing.getId())).thenReturn(Optional.of(performing));
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class))).thenReturn(mapped);

        LabOrderResponseDTO result = service.createLabOrder(
            request().performingHospitalId(performing.getId()).build(), Locale.ENGLISH);

        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getPerformingHospital()).isSameAs(performing);
        verify(routingNotifier).notifyPerformingLab(captor.getValue());
        assertThat(result).isSameAs(mapped);
    }

    @Test
    void createLabOrderNamingTheOrderingHospitalItselfIsAnInHouseOrder() {
        mockOrderLookups();
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class))).thenReturn(mapped);

        service.createLabOrder(request().performingHospitalId(ordering.getId()).build(), Locale.ENGLISH);

        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getPerformingHospital()).isNull();
        verify(hospitalRepository, never()).findById(performing.getId());
    }

    @Test
    void createLabOrderRefusesAnInactivePerformingLaboratory() {
        mockOrderLookups();
        performing.setActive(false);
        when(hospitalRepository.findById(performing.getId())).thenReturn(Optional.of(performing));

        LabOrderRequestDTO request = request().performingHospitalId(performing.getId()).build();
        assertThatThrownBy(() -> service.createLabOrder(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital");
        verify(labOrderRepository, never()).save(any());
    }

    @Test
    void createLabOrderRefusesASuspendedPerformingLaboratory() {
        mockOrderLookups();
        performing.setLifecycleState(HospitalLifecycleState.SUSPENDED);
        when(hospitalRepository.findById(performing.getId())).thenReturn(Optional.of(performing));

        LabOrderRequestDTO request = request().performingHospitalId(performing.getId()).build();
        assertThatThrownBy(() -> service.createLabOrder(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void createLabOrderWithAnUnknownPerformingLaboratoryIs404() {
        mockOrderLookups();
        UUID unknown = UUID.randomUUID();
        when(hospitalRepository.findById(unknown)).thenReturn(Optional.empty());

        LabOrderRequestDTO request = request().performingHospitalId(unknown).build();
        assertThatThrownBy(() -> service.createLabOrder(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── the scope predicate ───────────────────────────────────────────────

    @Test
    void getLabOrderByIdIsVisibleToTheOrderingHospital() {
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        when(labOrderMapper.toLabOrderResponseDTO(order)).thenReturn(mapped);

        assertThat(service.getLabOrderById(order.getId(), Locale.ENGLISH)).isSameAs(mapped);
    }

    @Test
    void getLabOrderByIdIsVisibleToThePerformingHospital() {
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(labOrderMapper.toLabOrderResponseDTO(order)).thenReturn(mapped);

        assertThat(service.getLabOrderById(order.getId(), Locale.ENGLISH)).isSameAs(mapped);
    }

    @Test
    void getLabOrderByIdIs404ForAThirdHospital() {
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(third.getId());

        UUID id = order.getId();
        assertThatThrownBy(() -> service.getLabOrderById(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(labOrderMapper, never()).toLabOrderResponseDTO(any());
    }

    @Test
    void updateLabOrderStaysWithTheOrderingHospital() {
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());

        UUID id = order.getId();
        LabOrderRequestDTO request = request().build();
        assertThatThrownBy(() -> service.updateLabOrder(id, request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(labOrderRepository, never()).save(any());
    }

    @Test
    void updateWithoutThePerformingFieldKeepsTheCurrentLaboratory() {
        // The shape every client that knows nothing of B1 sends: the field is
        // absent, and an absent field is not an instruction to re-route.
        mockOrderLookups();
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class))).thenReturn(mapped);

        LabOrderRequestDTO absent = request().build();
        assertThat(absent.hasPerformingHospitalId()).isFalse();
        service.updateLabOrder(order.getId(), absent, Locale.ENGLISH);

        assertThat(order.getPerformingHospital()).isSameAs(performing);
        verify(hospitalRepository, never()).findById(performing.getId());
        verify(routingNotifier, never()).notifyPerformingLab(any());
    }

    @Test
    void updateWithAnExplicitNullBringsTheOrderBackInHouse() {
        // The portal's "this hospital's laboratory" option. Absent could not
        // express this, so an outsourced order could never come home.
        mockOrderLookups();
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        when(labSpecimenRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of());
        when(labResultRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of());
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class))).thenReturn(mapped);

        LabOrderRequestDTO explicitNull = request().performingHospitalId(null).build();
        assertThat(explicitNull.hasPerformingHospitalId()).isTrue();
        service.updateLabOrder(order.getId(), explicitNull, Locale.ENGLISH);

        assertThat(order.getPerformingHospital()).isNull();
        verify(routingNotifier, never()).notifyPerformingLab(any());
    }

    @Test
    void updateWithAnExplicitNullIsStillRefusedOnceTheLaboratoryHasWorked() {
        mockOrderLookups();
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        when(labSpecimenRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of());
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        when(labResultRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of(result));

        UUID id = order.getId();
        LabOrderRequestDTO explicitNull = request().performingHospitalId(null).build();
        assertThatThrownBy(() -> service.updateLabOrder(id, explicitNull, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.ConflictException.class);
        assertThat(order.getPerformingHospital()).isSameAs(performing);
        verify(labOrderRepository, never()).save(any());
    }

    @Test
    void updateEchoingASuspendedCurrentLaboratoryDoesNotReCheckRoutability() {
        // Editing the notes of an order whose laboratory was suspended after
        // it was placed: the caller is not choosing that laboratory, so the
        // routability gate must not turn the edit into a 400.
        mockOrderLookups();
        performing.setLifecycleState(HospitalLifecycleState.SUSPENDED);
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class))).thenReturn(mapped);

        service.updateLabOrder(order.getId(),
            request().performingHospitalId(performing.getId()).build(), Locale.ENGLISH);

        assertThat(order.getPerformingHospital()).isSameAs(performing);
        verify(hospitalRepository, never()).findById(performing.getId());
        verify(routingNotifier, never()).notifyPerformingLab(any());
    }

    @Test
    void createIgnoresTheAbsentFieldAndOrdersInHouse() {
        mockOrderLookups();
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class))).thenReturn(mapped);

        service.createLabOrder(request().build(), Locale.ENGLISH);

        ArgumentCaptor<LabOrder> captor = ArgumentCaptor.forClass(LabOrder.class);
        verify(labOrderRepository).save(captor.capture());
        assertThat(captor.getValue().getPerformingHospital()).isNull();
    }

    @Test
    void updateCannotMoveTheOrderOnceTheLaboratoryHasRecordedAResult() {
        mockOrderLookups();
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        when(hospitalRepository.findById(third.getId())).thenReturn(Optional.of(third));
        when(labSpecimenRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of());
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        when(labResultRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of(result));

        UUID id = order.getId();
        LabOrderRequestDTO moveToThird = request().performingHospitalId(third.getId()).build();
        assertThatThrownBy(() -> service.updateLabOrder(id, moveToThird, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.ConflictException.class)
            .hasMessageContaining("cannot change");
        LabOrderRequestDTO bringInHouse = request().performingHospitalId(ordering.getId()).build();
        assertThatThrownBy(() -> service.updateLabOrder(id, bringInHouse, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.ConflictException.class);
        assertThat(order.getPerformingHospital()).isSameAs(performing);
        verify(labOrderRepository, never()).save(any());
    }

    @Test
    void updateCannotMoveTheOrderOnceASpecimenWasCollected() {
        mockOrderLookups();
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        when(hospitalRepository.findById(third.getId())).thenReturn(Optional.of(third));
        when(labSpecimenRepository.findByLabOrder_Id(order.getId()))
            .thenReturn(List.of(new com.example.hms.model.LabSpecimen()));

        UUID id = order.getId();
        LabOrderRequestDTO moveToThird = request().performingHospitalId(third.getId()).build();
        assertThatThrownBy(() -> service.updateLabOrder(id, moveToThird, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.ConflictException.class);
        verify(labOrderRepository, never()).save(any());
    }

    @Test
    void updateReRoutesAnUntouchedOrderAndNotifiesTheNewLaboratory() {
        mockOrderLookups();
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        when(hospitalRepository.findById(third.getId())).thenReturn(Optional.of(third));
        when(labSpecimenRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of());
        when(labResultRepository.findByLabOrder_Id(order.getId())).thenReturn(List.of());
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class))).thenReturn(mapped);

        service.updateLabOrder(order.getId(), request().performingHospitalId(third.getId()).build(), Locale.ENGLISH);

        assertThat(order.getPerformingHospital()).isSameAs(third);
        verify(routingNotifier).notifyPerformingLab(order);
    }

    @Test
    void updateKeepingTheSameLaboratoryDoesNotNotifyAgain() {
        mockOrderLookups();
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labOrderMapper.toLabOrderResponseDTO(any(LabOrder.class))).thenReturn(mapped);

        service.updateLabOrder(order.getId(), request().performingHospitalId(performing.getId()).build(), Locale.ENGLISH);

        verify(routingNotifier, never()).notifyPerformingLab(any());
        verify(hospitalRepository, never()).findById(performing.getId());
        verify(labSpecimenRepository, never()).findByLabOrder_Id(any());
    }

    @Test
    void transitionIsAuthorisedAtThePerformingLaboratory() {
        order.setStatus(LabOrderStatus.PENDING);
        UUID labUserId = UUID.randomUUID();
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(roleValidator.getCurrentUserId()).thenReturn(labUserId);
        when(roleValidator.isLabStaff(labUserId, performing.getId())).thenReturn(true);
        when(labOrderRepository.save(order)).thenReturn(order);
        when(labOrderMapper.toLabOrderResponseDTO(order)).thenReturn(mapped);

        service.transitionLabOrderStatus(order.getId(), LabOrderStatus.COLLECTED, Locale.ENGLISH);

        assertThat(order.getStatus()).isEqualTo(LabOrderStatus.COLLECTED);
        verify(roleValidator, never()).isLabStaff(labUserId, ordering.getId());
    }

    @Test
    void cancellationStaysWithTheOrderingHospitalEvenWhenActingAtTheLaboratory() {
        UUID labUserId = UUID.randomUUID();
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(roleValidator.getCurrentUserId()).thenReturn(labUserId);
        when(roleValidator.isLabManager(labUserId, ordering.getId())).thenReturn(false);
        when(roleValidator.isHospitalAdmin(labUserId, ordering.getId())).thenReturn(false);
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(false);

        UUID id = order.getId();
        assertThatThrownBy(() -> service.transitionLabOrderStatus(id, LabOrderStatus.CANCELLED, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cancel");
        verify(roleValidator, never()).isLabManager(labUserId, performing.getId());
    }

    @Test
    void transitionIs404ForAThirdHospital() {
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(third.getId());

        UUID id = order.getId();
        assertThatThrownBy(() -> service.transitionLabOrderStatus(id, LabOrderStatus.PENDING, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── worklists ─────────────────────────────────────────────────────────

    @Test
    void listReadsOrdersHandledByTheActiveHospital() {
        UUID requester = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(roleValidator.getCurrentUserId()).thenReturn(requester);
        when(labOrderRepository.findHandledBy(performing.getId())).thenReturn(List.of(order));
        when(labOrderMapper.toLabOrderResponseDTO(order)).thenReturn(mapped);

        assertThat(service.getAllLabOrders(Locale.ENGLISH)).containsExactly(mapped);

        // The worklist is where this feature is used from, so it is where the
        // disclosure has to be accounted.
        verify(reachRecorder).recordReach(eq(patient.getId()), eq(performing.getId()), eq(requester),
            any(), eq(java.util.Map.of(ordering.getId().toString(), 1L)), any());
    }

    @Test
    void readingOneOutsourcedOrderIsAccountedAsADisclosure() {
        UUID requester = UUID.randomUUID();
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(roleValidator.getCurrentUserId()).thenReturn(requester);
        when(labOrderMapper.toLabOrderResponseDTO(order)).thenReturn(mapped);

        service.getLabOrderById(order.getId(), Locale.ENGLISH);

        verify(reachRecorder).recordReach(eq(patient.getId()), eq(performing.getId()), eq(requester),
            any(), eq(java.util.Map.of(ordering.getId().toString(), 1L)), any());
    }

    @Test
    void theOrderingHospitalsOwnReadIsNotADisclosure() {
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        when(labOrderMapper.toLabOrderResponseDTO(order)).thenReturn(mapped);

        service.getLabOrderById(order.getId(), Locale.ENGLISH);

        verifyNoInteractions(reachRecorder);
    }

    @Test
    void searchPassesTheActiveHospitalToTheHandledByPredicate() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        PageRequest page = PageRequest.of(0, 20);
        when(labOrderRepository.search(performing.getId(), null, null, null, page))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(order)));
        when(labOrderMapper.toLabOrderResponseDTO(order)).thenReturn(mapped);

        assertThat(service.searchLabOrders(null, null, null, page, Locale.ENGLISH).getContent())
            .containsExactly(mapped);
    }

    @Test
    void byStatusReadsOrdersHandledByTheActiveHospital() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(labOrderRepository.findByStatusHandledBy(LabOrderStatus.ORDERED, performing.getId()))
            .thenReturn(List.of(order));
        when(labOrderMapper.toLabOrderResponseDTO(order)).thenReturn(mapped);

        assertThat(service.getLabOrdersByStatus(LabOrderStatus.ORDERED, Locale.ENGLISH)).containsExactly(mapped);
    }

    @Test
    void byTestDefinitionKeepsOrdersHandledByTheActiveHospitalOnly() {
        LabOrder foreign = LabOrder.builder().hospital(third).labTestDefinition(definition).build();
        foreign.setId(UUID.randomUUID());
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(labOrderRepository.findByLabTestDefinition_Id(definition.getId())).thenReturn(List.of(order, foreign));
        when(labOrderMapper.toLabOrderResponseDTO(order)).thenReturn(mapped);

        assertThat(service.getLabOrdersByLabTestDefinitionId(definition.getId(), Locale.ENGLISH))
            .containsExactly(mapped);
        verify(labOrderMapper, never()).toLabOrderResponseDTO(foreign);
    }

    @Test
    void byPatientIncludesOrdersPerformedHereAndAccountsThemAsADisclosure() {
        UUID requester = UUID.randomUUID();
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(roleValidator.getCurrentUserId()).thenReturn(requester);
        when(recordAccessPolicy.readableHospitalIds(requester, patient.getId(), performing.getId()))
            .thenReturn(java.util.Set.of(performing.getId()));
        when(labOrderRepository.findByPatientIdReadableOrPerformedAt(
            eq(patient.getId()), any(), eq(performing.getId()))).thenReturn(List.of(order));
        when(labOrderMapper.toLabOrderResponseDTO(order)).thenReturn(mapped);

        assertThat(service.getLabOrdersByPatientId(patient.getId(), Locale.ENGLISH)).containsExactly(mapped);

        // The order belongs to the ordering hospital and was surfaced at the
        // laboratory running it: a disclosure, permitted or not.
        ArgumentCaptor<java.util.Map<String, Long>> reach = ArgumentCaptor.captor();
        verify(reachRecorder).recordReach(eq(patient.getId()), eq(performing.getId()), eq(requester),
            any(), reach.capture(), any());
        assertThat(reach.getValue()).containsExactly(entry(ordering.getId().toString(), 1L));
    }

    // ── the candidate laboratories ────────────────────────────────────────

    @Test
    void performingLabsAreEveryActiveHospitalButTheActingOne() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        when(hospitalRepository.findByActiveTrueAndLifecycleStateOrderByNameAsc(HospitalLifecycleState.ACTIVE))
            .thenReturn(List.of(performing, ordering, third));

        List<PerformingLabOptionDTO> labs = service.listPerformingLabs();

        assertThat(labs).extracting(PerformingLabOptionDTO::getId)
            .containsExactly(performing.getId(), third.getId());
        assertThat(labs.get(0).getName()).isEqualTo("Central Laboratory");
        assertThat(labs.get(0).getCode()).isEqualTo("LAB");
    }

    @Test
    void performingLabsForASuperAdminInGlobalViewAreEveryActiveHospital() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(hospitalRepository.findByActiveTrueAndLifecycleStateOrderByNameAsc(HospitalLifecycleState.ACTIVE))
            .thenReturn(List.of(performing, ordering));

        assertThat(service.listPerformingLabs()).hasSize(2);
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private void mockOrderLookups() {
        when(patientRepository.findByIdUnscoped(patient.getId())).thenReturn(Optional.of(patient));
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(hospitalRepository.findById(ordering.getId())).thenReturn(Optional.of(ordering));
        when(patientHospitalRegistrationRepository.existsByPatientIdAndHospitalId(patient.getId(), ordering.getId()))
            .thenReturn(true);
        when(roleValidator.canOrderLabTests(staff.getUser().getId(), ordering.getId())).thenReturn(true);
        when(labTestDefinitionRepository.findById(definition.getId())).thenReturn(Optional.of(definition));
        when(assignmentRepository.findById(assignment.getId())).thenReturn(Optional.of(assignment));
        lenient().when(labOrderRepository.existsByPatient_IdAndLabTestDefinition_IdAndOrderDatetime(
            eq(patient.getId()), eq(definition.getId()), any(LocalDateTime.class))).thenReturn(false);
    }

    private LabOrderRequestDTO.LabOrderRequestDTOBuilder request() {
        return LabOrderRequestDTO.builder()
            .patientId(patient.getId())
            .hospitalId(ordering.getId())
            .orderingStaffId(staff.getId())
            .labTestDefinitionId(definition.getId())
            .assignmentId(assignment.getId())
            .testName("CBC")
            .status(LabOrderStatus.ORDERED.name())
            .clinicalIndication("Fatigue")
            .medicalNecessityNote("Rule out anaemia")
            .primaryDiagnosisCode("D64.9")
            .orderChannel(LabOrderChannel.ELECTRONIC.name())
            .documentationSharedWithLab(true)
            .providerSignature("signed")
            .standingOrder(false)
            .orderDatetime(LocalDateTime.now());
    }

    private static Hospital hospital(String name, String code) {
        Hospital hospital = Hospital.builder().name(name).code(code).build();
        hospital.setId(UUID.randomUUID());
        return hospital;
    }
}
