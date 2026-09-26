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
import com.example.hms.payload.dto.CriticalValueReadBackRequestDTO;
import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabResultSignatureRequestDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabReflexRuleRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.lab.LabResultEntryGuard;
import com.example.hms.utility.RoleValidator;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A null hospital scope is "super-admin, unscoped" only when the verified
 * super-admin flag says so.
 *
 * <p>{@code RoleValidator.requireActiveHospitalId()} also returns null from its
 * step-4 fallback on the AUTHORITIES collection, which can be inflated. Every
 * lab-result guard used to read either null as "allow", so that principal got,
 * amended, deleted, acknowledged, read back, released and signed any tenant's
 * result. Here it is refused, with the same answer a nonexistent id gets; a
 * verified super-admin keeps the global view; and the B1 predicate for a pinned
 * caller is untouched, so the performing laboratory still reaches its results.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabResultServiceImplNullScopeTest {

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
    @Mock private com.example.hms.service.lab.LabOrderRoutingNotifier routingNotifier;
    @Mock private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;
    @Mock private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;

    @InjectMocks
    private LabResultServiceImpl service;

    private Hospital ordering;
    private Hospital performing;
    private LabOrder order;
    private LabResult result;
    private UserRoleHospitalAssignment labAssignment;
    private final UUID missingId = UUID.randomUUID();
    private final LabResultResponseDTO mapped = LabResultResponseDTO.builder().id("mapped").build();

    @BeforeEach
    void setUp() {
        ordering = hospital();
        performing = hospital();

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

        when(labResultRepository.findById(result.getId())).thenReturn(Optional.of(result));
        when(labResultRepository.findById(missingId)).thenReturn(Optional.empty());
        when(labOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(labResultMapper.toResponseDTO(any(LabResult.class))).thenReturn(mapped);
        when(labResultRepository.findTrendReadableAt(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any()))
            .thenReturn(List.of(result));
        when(criticalValueNotificationService.recordReadBack(any(), any(), any(), any())).thenReturn(result);
        when(authService.getCurrentUserId()).thenReturn(UUID.randomUUID());
    }

    /** requireActiveHospitalId()'s step 4: null because the AUTHORITIES say super-admin; the verified flag does not. */
    @Nested
    class UnverifiedNullScope {

        @BeforeEach
        void scope() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(false);
        }

        @Test
        void getIsRefusedExactlyLikeAMissingResult() {
            assertRefusedLikeMissing(id -> service.getLabResultById(id, Locale.ENGLISH));
        }

        @Test
        void updateIsRefusedExactlyLikeAMissingResult() {
            assertRefusedLikeMissing(id -> service.updateLabResult(id, request(), Locale.ENGLISH));
            verify(labResultRepository, never()).save(any());
        }

        @Test
        void deleteIsRefusedExactlyLikeAMissingResult() {
            assertRefusedLikeMissing(id -> service.deleteLabResult(id, Locale.ENGLISH));
            verify(labResultRepository, never()).deleteById(any());
        }

        @Test
        void readBackIsRefusedExactlyLikeAMissingResult() {
            assertRefusedLikeMissing(id -> service.recordCriticalReadBack(id, readBack(), Locale.ENGLISH));
            verify(criticalValueNotificationService, never()).recordReadBack(any(), any(), any(), any());
        }

        @Test
        void acknowledgeReleaseSignAndCompareAreRefusedExactlyLikeAMissingResult() {
            assertRefusedLikeMissing(id -> service.acknowledgeLabResult(id, Locale.ENGLISH));
            assertRefusedLikeMissing(id -> service.releaseLabResult(id, Locale.ENGLISH));
            assertRefusedLikeMissing(id -> service.signLabResult(id, new LabResultSignatureRequestDTO(), Locale.ENGLISH));
            assertRefusedLikeMissing(id -> service.compareLabResults(id, Locale.ENGLISH));
            verify(labResultRepository, never()).save(any());
        }

        @Test
        void aResultCannotBeAttachedToAnyTenantsOrder() {
            // isHandledBy(null) is true, so the order-side guard let this
            // principal attach a result to any hospital's order.
            LabResultRequestDTO request = request();
            Throwable refusal = catchThrowable(() -> service.createLabResult(request, Locale.ENGLISH));

            assertThat(refusal).isExactlyInstanceOf(ResourceNotFoundException.class);
            verify(labResultRepository, never()).save(any());
        }

        @Test
        void theListsRefuseInsteadOfReadingEveryTenant() {
            PageRequest page = PageRequest.of(0, 20);
            UUID requestedHospital = UUID.randomUUID();
            LocalDateTime since = LocalDateTime.now().minusDays(1);

            assertThat(catchThrowable(() -> service.getLabResultsPage(page, Locale.ENGLISH)))
                .isExactlyInstanceOf(BusinessException.class);
            assertThat(catchThrowable(() -> service.getCriticalResults(requestedHospital, since, Locale.ENGLISH)))
                .isExactlyInstanceOf(BusinessException.class);
            assertThat(catchThrowable(() ->
                    service.getCriticalResultsRequiringAcknowledgment(requestedHospital, Locale.ENGLISH)))
                .isExactlyInstanceOf(BusinessException.class);
            verify(labResultRepository, never()).findAll(any(org.springframework.data.domain.Pageable.class));
            verify(labResultRepository, never()).findHandledByHospitals(any());
        }
    }

    /** requireActiveHospitalId()'s step 1: a real super-admin in global view. */
    @Nested
    class VerifiedSuperAdminGlobalView {

        @BeforeEach
        void scope() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(null);
            when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
        }

        @Test
        void isStillServedAcrossTenants() {
            assertThat(service.getLabResultById(result.getId(), Locale.ENGLISH)).isSameAs(mapped);
            assertThat(service.recordCriticalReadBack(result.getId(), readBack(), Locale.ENGLISH)).isSameAs(mapped);

            service.deleteLabResult(result.getId(), Locale.ENGLISH);
            verify(labResultRepository).deleteById(result.getId());
        }

        @Test
        void stillPagesEveryTenant() {
            PageRequest page = PageRequest.of(0, 20);
            when(labResultRepository.findAll(page))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(result)));

            assertThat(service.getLabResultsPage(page, Locale.ENGLISH).getContent()).containsExactly(mapped);
        }
    }

    /** B1 is unchanged for a pinned caller: ordering OR performing hospital. */
    @Nested
    class PinnedCaller {

        @Test
        void thePerformingLaboratoryStillReachesItsResult() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(performing.getId());

            assertThat(service.getLabResultById(result.getId(), Locale.ENGLISH)).isSameAs(mapped);
            assertThat(service.recordCriticalReadBack(result.getId(), readBack(), Locale.ENGLISH)).isSameAs(mapped);
            service.deleteLabResult(result.getId(), Locale.ENGLISH);

            verify(labResultRepository).deleteById(result.getId());
            // A pinned caller never needed the super-admin flag.
            verify(roleValidator, never()).isSuperAdminFromJwtClaim();
        }

        @Test
        void theOrderingHospitalStillReachesItsResult() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(ordering.getId());

            assertThat(service.getLabResultById(result.getId(), Locale.ENGLISH)).isSameAs(mapped);
        }

        @Test
        void aThirdHospitalIsRefusedExactlyLikeAMissingResult() {
            when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

            assertRefusedLikeMissing(id -> service.getLabResultById(id, Locale.ENGLISH));
            assertRefusedLikeMissing(id -> service.deleteLabResult(id, Locale.ENGLISH));
            verify(labResultRepository, never()).deleteById(any());
        }
    }

    @FunctionalInterface
    private interface ById {
        void call(UUID id);
    }

    /** The refusal for the real id is the refusal for an id that does not exist: same type, same message. */
    private void assertRefusedLikeMissing(ById call) {
        ThrowingCallable onReal = () -> call.call(result.getId());
        ThrowingCallable onMissing = () -> call.call(missingId);
        Throwable real = catchThrowable(onReal);
        Throwable missing = catchThrowable(onMissing);

        assertThat(missing).isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThat(real).isExactlyInstanceOf(ResourceNotFoundException.class);
        assertThat(real.getMessage()).isEqualTo(missing.getMessage());
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

    private static CriticalValueReadBackRequestDTO readBack() {
        CriticalValueReadBackRequestDTO request = new CriticalValueReadBackRequestDTO();
        request.setRepeatedValue("12.1");
        return request;
    }

    private static Hospital hospital() {
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        return hospital;
    }
}
