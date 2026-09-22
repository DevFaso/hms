package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit gap B1 on results: the performing laboratory enters, releases and
 * signs the result of an order sent to it — judged by its roles THERE — and
 * the ordering hospital reads it; a third hospital gets 404 throughout.
 */
@ExtendWith(MockitoExtension.class)
class LabResultServiceImplPerformingHospitalTest {

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

    private Hospital ordering;
    private Hospital performing;
    private Hospital third;
    private LabOrder order;
    private LabResult result;
    private UserRoleHospitalAssignment labAssignment;
    private final UUID labUserId = UUID.randomUUID();
    private final LabResultResponseDTO mapped = LabResultResponseDTO.builder().id("mapped").build();

    @BeforeEach
    void setUp() {
        ordering = hospital("Ordering Hospital");
        performing = hospital("Central Laboratory");
        third = hospital("Unrelated Clinic");

        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        LabTestDefinition definition = new LabTestDefinition();
        definition.setId(UUID.randomUUID());
        definition.setName("CBC");

        order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setHospital(ordering);
        order.setPerformingHospital(performing);
        order.setPatient(patient);
        order.setLabTestDefinition(definition);

        labAssignment = new UserRoleHospitalAssignment();
        labAssignment.setId(UUID.randomUUID());
        labAssignment.setHospital(performing);

        result = LabResult.builder()
            .labOrder(order)
            .resultValue("12.1")
            .resultDate(LocalDateTime.now())
            .assignment(labAssignment)
            .build();
        result.setId(UUID.randomUUID());
    }

    @Test
    void performingLaboratoryEntersAResultJudgedByItsRolesThere() {
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(authService.getCurrentUserId()).thenReturn(labUserId);
        when(roleValidator.hasRole(labUserId, performing.getId(), "ROLE_LAB_SCIENTIST")).thenReturn(true);
        when(assignmentRepository.findById(labAssignment.getId())).thenReturn(Optional.of(labAssignment));
        when(labResultMapper.toEntity(any(), any(), any())).thenReturn(result);
        when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));
        when(labReflexRuleRepository.findByTriggerTestDefinition_IdAndActiveTrue(any())).thenReturn(List.of());
        when(labResultMapper.toResponseDTO(result)).thenReturn(mapped);

        LabResultResponseDTO response = service.createLabResult(request(), Locale.ENGLISH);

        assertThat(response).isSameAs(mapped);
        verify(roleValidator, never()).hasRole(labUserId, ordering.getId(), "ROLE_LAB_SCIENTIST");
    }

    @Test
    void thirdHospitalCannotEnterAResult() {
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(third.getId());

        LabResultRequestDTO request = request();
        assertThatThrownBy(() -> service.createLabResult(request, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(labResultRepository, never()).save(any());
    }

    @Test
    void orderingHospitalActorIsStillJudgedByTheOrderingHospitalRoles() {
        UUID doctorId = UUID.randomUUID();
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        when(authService.getCurrentUserId()).thenReturn(doctorId);
        when(roleValidator.hasRole(doctorId, ordering.getId(), "ROLE_LAB_SCIENTIST")).thenReturn(false);
        when(roleValidator.isMidwife(doctorId, ordering.getId())).thenReturn(false);
        when(roleValidator.isDoctor(doctorId, ordering.getId())).thenReturn(false);
        when(roleValidator.isNurse(doctorId, ordering.getId())).thenReturn(false);
        when(roleValidator.isLabTechnician(doctorId, ordering.getId())).thenReturn(false);
        when(roleValidator.isLabManager(doctorId, ordering.getId())).thenReturn(false);
        when(roleValidator.hasRole(doctorId, ordering.getId(), "ROLE_LAB_DIRECTOR")).thenReturn(false);
        when(roleValidator.hasRole(doctorId, ordering.getId(), "ROLE_QUALITY_MANAGER")).thenReturn(false);

        LabResultRequestDTO request = request();
        assertThatThrownBy(() -> service.createLabResult(request, Locale.ENGLISH))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("lab or clinical role");
    }

    @Test
    void resultIsReadableByBothHospitalsAndNotByAThird() {
        when(labResultRepository.findById(result.getId())).thenReturn(Optional.of(result));
        when(labResultMapper.toResponseDTO(result)).thenReturn(mapped);
        when(labResultRepository
            .findTop12ByLabOrder_Patient_IdAndLabOrder_LabTestDefinition_IdOrderByResultDateDesc(any(), any()))
            .thenReturn(List.of());

        when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());
        assertThat(service.getLabResultById(result.getId(), Locale.ENGLISH)).isSameAs(mapped);

        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        assertThat(service.getLabResultById(result.getId(), Locale.ENGLISH)).isSameAs(mapped);

        when(roleValidator.requireActiveHospitalId()).thenReturn(third.getId());
        UUID id = result.getId();
        assertThatThrownBy(() -> service.getLabResultById(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void performingLaboratoryReleasesTheResultJudgedByItsRolesThere() {
        when(labResultRepository.findById(result.getId())).thenReturn(Optional.of(result));
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(authService.getCurrentUserId()).thenReturn(labUserId);
        when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(false);
        when(roleValidator.isLabScientist(labUserId, performing.getId())).thenReturn(true);
        when(labResultMapper.toResponseDTO(result)).thenReturn(mapped);

        LabResultResponseDTO response = service.releaseLabResult(result.getId(), Locale.ENGLISH);

        assertThat(response).isSameAs(mapped);
        assertThat(result.isReleased()).isTrue();
        assertThat(result.getReleasedByUserId()).isEqualTo(labUserId);
        verify(roleValidator, never()).isLabScientist(labUserId, ordering.getId());
    }

    @Test
    void thirdHospitalCannotReleaseOrSign() {
        when(labResultRepository.findById(result.getId())).thenReturn(Optional.of(result));
        when(roleValidator.requireActiveHospitalId()).thenReturn(third.getId());

        UUID id = result.getId();
        assertThatThrownBy(() -> service.releaseLabResult(id, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.signLabResult(id, null, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(result.isReleased()).isFalse();
        assertThat(result.getSignedAt()).isNull();
    }

    @Test
    void performingLaboratorySignsTheResultJudgedByItsRolesThere() {
        when(labResultRepository.findById(result.getId())).thenReturn(Optional.of(result));
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(authService.getCurrentUserId()).thenReturn(labUserId);
        when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(false);
        when(roleValidator.isDoctor(labUserId, performing.getId())).thenReturn(false);
        when(roleValidator.isMidwife(labUserId, performing.getId())).thenReturn(false);
        when(roleValidator.isLabScientist(labUserId, performing.getId())).thenReturn(true);
        when(labResultMapper.toResponseDTO(result)).thenReturn(mapped);

        service.signLabResult(result.getId(), null, Locale.ENGLISH);

        assertThat(result.getSignedByUserId()).isEqualTo(labUserId);
    }

    @Test
    void theReleaseWorklistOfThePerformingLaboratoryHoldsItsOutsourcedResults() {
        PageRequest page = PageRequest.of(0, 20);
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(labResultRepository.findPendingReleaseHandledBy(performing.getId(), page))
            .thenReturn(new PageImpl<>(List.of(result)));
        when(labResultMapper.toResponseDTO(result)).thenReturn(mapped);

        assertThat(service.getPendingRelease(page, Locale.ENGLISH).getContent()).containsExactly(mapped);
    }

    @Test
    void theReleaseWorklistOfAThirdHospitalHoldsNothingOfThisOrder() {
        // The predicate is the repository's; what this pins is that the queue
        // asks for the acting hospital and shows only what comes back for it.
        PageRequest page = PageRequest.of(0, 20);
        when(roleValidator.requireActiveHospitalId()).thenReturn(third.getId());
        when(labResultRepository.findPendingReleaseHandledBy(third.getId(), page))
            .thenReturn(new PageImpl<>(List.of()));

        assertThat(service.getPendingRelease(page, Locale.ENGLISH).getContent()).isEmpty();
        verify(labResultRepository, never()).findPendingReleaseHandledBy(eq(ordering.getId()), any());
        verify(labResultRepository, never()).findPendingReleaseHandledBy(eq(performing.getId()), any());
    }

    @Test
    void pagedResultsReadWhatTheHospitalOrdersOrPerforms() {
        PageRequest page = PageRequest.of(0, 10);
        when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());
        when(labResultRepository.findHandledByHospital(performing.getId(), page))
            .thenReturn(new PageImpl<>(List.of(result)));
        when(labResultMapper.toResponseDTO(result)).thenReturn(mapped);

        assertThat(service.getLabResultsPage(page, Locale.ENGLISH).getContent()).containsExactly(mapped);
    }

    @Test
    void allResultsReadWhatTheAssignedHospitalsOrderOrPerform() {
        when(authService.getCurrentUserId()).thenReturn(labUserId);
        when(authService.hasRole("ROLE_SUPER_ADMIN")).thenReturn(false);
        when(assignmentRepository.findByUser_IdAndActiveTrue(labUserId)).thenReturn(List.of(labAssignment));
        when(labResultRepository.findHandledByHospitals(Set.of(performing.getId()))).thenReturn(List.of(result));
        when(labResultMapper.toResponseDTO(result)).thenReturn(mapped);

        assertThat(service.getAllLabResults(Locale.ENGLISH)).containsExactly(mapped);
    }

    private LabResultRequestDTO request() {
        return LabResultRequestDTO.builder()
            .labOrderId(order.getId())
            .assignmentId(labAssignment.getId())
            .patientId(order.getPatient().getId())
            .resultValue("12.1")
            .resultDate(LocalDateTime.now())
            .build();
    }

    private static Hospital hospital(String name) {
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName(name);
        return hospital;
    }
}
