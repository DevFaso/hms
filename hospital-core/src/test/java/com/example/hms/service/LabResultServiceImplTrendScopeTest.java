package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.LabResultComparisonDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.payload.dto.LabResultTrendPointDTO;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabReflexRuleRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.lab.LabResultEntryGuard;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /lab-results/patient/{patientId}/test/{testDefinitionId}/compare-sequential}
 * had no tenant check at all: the unscoped finder, by the patient id in the
 * URL, returned any hospital's patient's last twelve values, with the name, to
 * any clinician anywhere. The trend on a single result carried the same
 * patient's values from every hospital too.
 *
 * <p>The caller in every test here is at hospital B. A readable row is one
 * whose order B placed or B's laboratory performed (B1), or one placed at a
 * hospital the treatment relationship opens. The repository is stubbed to hand
 * back more than that, so what is asserted is the rule the service applies,
 * not a query that happens to be narrow.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabResultServiceImplTrendScopeTest {

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
    @Mock private CrossHospitalReachRecorder reachRecorder;
    @Mock private RecordAccessPolicy recordAccessPolicy;

    @InjectMocks
    private LabResultServiceImpl service;

    private final Hospital hospitalA = hospital();
    private final Hospital hospitalB = hospital();
    private final Hospital hospitalT = hospital();
    private final UUID actorId = UUID.randomUUID();
    private final UUID unknownPatientId = UUID.randomUUID();
    private Patient patient;
    private LabTestDefinition test;

    @BeforeEach
    void setUp() {
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Awa");
        patient.setLastName("Traore");
        test = new LabTestDefinition();
        test.setId(UUID.randomUUID());
        test.setName("Hemoglobin");
        test.setTestCode("HGB");

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalB.getId());
        when(authService.getCurrentUserId()).thenReturn(actorId);
        when(recordAccessPolicy.readableHospitalIds(any(), any(), any())).thenReturn(Set.of(hospitalB.getId()));
        when(labResultMapper.toTrendPointDTO(any(LabResult.class))).thenAnswer(inv -> {
            LabResult row = inv.getArgument(0);
            return LabResultTrendPointDTO.builder()
                .labResultId(row.getId().toString())
                .resultDate(row.getResultDate())
                .resultValue(row.getResultValue())
                .build();
        });
    }

    @Test
    void aClinicianAtBGetsNothingForAPatientOnlyAtA_exactlyLikeAnUnknownPatient() {
        LabResult atA = row(hospitalA, null, 1);
        when(labResultRepository.findTrendReadableAt(eq(patient.getId()), eq(test.getId()), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
            .thenReturn(List.of(atA));
        when(labResultRepository.findTrendReadableAt(eq(unknownPatientId), eq(test.getId()), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
            .thenReturn(List.of());

        List<LabResultComparisonDTO> foreign = service.compareSequentialResults(patient.getId(), test.getId(), Locale.ENGLISH);
        List<LabResultComparisonDTO> unknown = service.compareSequentialResults(unknownPatientId, test.getId(), Locale.ENGLISH);

        assertThat(foreign).isEmpty();
        assertThat(foreign).isEqualTo(unknown);
        verify(labResultRepository, never()).findTrendReadableAt(any(), any(), any(), any(), eq(true), any());
        verify(reachRecorder, never()).recordReach(any(), any(), any(), any(), anyMap(), any());
    }

    @Test
    void theQueryIsAskedForTheCallersReadableHospitalsOnly() {
        service.compareSequentialResults(patient.getId(), test.getId(), Locale.ENGLISH);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> readable = ArgumentCaptor.forClass(Collection.class);
        verify(labResultRepository).findTrendReadableAt(eq(patient.getId()), eq(test.getId()),
            readable.capture(), eq(hospitalB.getId()), eq(false), any());
        assertThat(readable.getValue()).containsExactly(hospitalB.getId());
    }

    @Test
    void theOrderingHospitalAndThePerformingLaboratoryBothSeeTheirs() {
        LabResult orderedAtB = row(hospitalB, null, 1);
        LabResult performedAtBForA = row(hospitalA, hospitalB, 2);
        LabResult atA = row(hospitalA, null, 3);
        when(labResultRepository.findTrendReadableAt(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
            .thenReturn(List.of(orderedAtB, performedAtBForA, atA));

        List<LabResultComparisonDTO> comparisons =
            service.compareSequentialResults(patient.getId(), test.getId(), Locale.ENGLISH);

        assertThat(comparisons).extracting(c -> c.getCurrentResult().getLabResultId())
            .containsExactly(orderedAtB.getId().toString(), performedAtBForA.getId().toString());
        // The performing laboratory surfacing A's order is accounted as such.
        verify(reachRecorder).recordBatchedReach(anyMap(), eq(hospitalB.getId()), eq(actorId), any(), any());
    }

    @Test
    void aTreatmentRelationshipOpensTheOtherHospitalsRowsAndIsAccounted() {
        when(recordAccessPolicy.readableHospitalIds(actorId, patient.getId(), hospitalB.getId()))
            .thenReturn(Set.of(hospitalB.getId(), hospitalT.getId()));
        LabResult atT = row(hospitalT, null, 1);
        when(labResultRepository.findTrendReadableAt(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any())).thenReturn(List.of(atT));

        List<LabResultComparisonDTO> comparisons =
            service.compareSequentialResults(patient.getId(), test.getId(), Locale.ENGLISH);

        assertThat(comparisons).hasSize(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Long>> reach = ArgumentCaptor.forClass(Map.class);
        verify(reachRecorder).recordReach(eq(patient.getId()), eq(hospitalB.getId()), eq(actorId), any(),
            reach.capture(), any());
        assertThat(reach.getValue()).containsEntry(hospitalT.getId().toString(), 1L);
    }

    @Test
    void noHospitalScopeIsRefusedBeforeAnyRowIsRead_evenForAVerifiedSuperAdmin() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
        UUID patientId = patient.getId();
        UUID testId = test.getId();

        assertThat(catchThrowable(() -> service.compareSequentialResults(patientId, testId, Locale.ENGLISH)))
            .isExactlyInstanceOf(BusinessException.class);
        verify(labResultRepository, never()).findTrendReadableAt(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void theTrendOnAResultBHoldsCarriesNoOtherHospitalsValues() {
        LabResult mine = row(hospitalB, null, 1);
        LabResult atA = row(hospitalA, null, 2);
        when(labResultRepository.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(labResultMapper.toResponseDTO(mine)).thenReturn(LabResultResponseDTO.builder().id("mine").build());
        when(labResultRepository.findTrendReadableAt(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
            .thenReturn(List.of(mine, atA));

        LabResultResponseDTO response = service.getLabResultById(mine.getId(), Locale.ENGLISH);

        assertThat(response.getTrendHistory()).extracting(LabResultTrendPointDTO::getLabResultId)
            .containsExactly(mine.getId().toString());
    }

    @Test
    void theTrendOnAComparisonCarriesNoOtherHospitalsValues() {
        LabResult mine = row(hospitalB, null, 1);
        LabResult atA = row(hospitalA, null, 2);
        when(labResultRepository.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(labResultRepository.findTrendReadableAt(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
            .thenReturn(List.of(mine, atA));

        LabResultComparisonDTO comparison = service.compareLabResults(mine.getId(), Locale.ENGLISH);

        assertThat(comparison.getTrendHistory()).extracting(LabResultTrendPointDTO::getLabResultId)
            .containsExactly(mine.getId().toString());
        assertThat(comparison.getPreviousResult()).isNull();
    }

    @Test
    void comparingAResultAnotherHospitalOrderedIsAccounted() {
        // /compare returns the result with the patient's name. It used to pass
        // the result to the accounting as "already accounted" while nothing
        // had accounted it: a performing-lab comparison left no disclosure row.
        LabResult performedAtBForA = row(hospitalA, hospitalB, 1);
        when(labResultRepository.findById(performedAtBForA.getId())).thenReturn(Optional.of(performedAtBForA));
        when(labResultRepository.findTrendReadableAt(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
            .thenReturn(List.of());

        service.compareLabResults(performedAtBForA.getId(), Locale.ENGLISH);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, Map<String, Long>>> batch = ArgumentCaptor.forClass(Map.class);
        verify(reachRecorder).recordBatchedReach(batch.capture(), eq(hospitalB.getId()), eq(actorId), any(), any());
        assertThat(batch.getValue()).containsEntry(patient.getId(), Map.of(hospitalA.getId().toString(), 1L));
    }

    @Test
    void aResultAndItsTrendAreOneDisclosure() {
        // getLabResultById used to write one batch for the result and a second
        // for its trend: two disclosures on the patient's report for one read.
        LabResult shown = row(hospitalA, hospitalB, 1);
        LabResult earlier = row(hospitalA, hospitalB, 5);
        when(labResultRepository.findById(shown.getId())).thenReturn(Optional.of(shown));
        when(labResultMapper.toResponseDTO(shown)).thenReturn(LabResultResponseDTO.builder().id("shown").build());
        when(labResultRepository.findTrendReadableAt(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
            .thenReturn(List.of(shown, earlier));

        service.getLabResultById(shown.getId(), Locale.ENGLISH);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<UUID, Map<String, Long>>> batch = ArgumentCaptor.forClass(Map.class);
        verify(reachRecorder, org.mockito.Mockito.times(1))
            .recordBatchedReach(batch.capture(), eq(hospitalB.getId()), eq(actorId), any(), any());
        // The shown result is also the newest trend point: counted once, not twice.
        assertThat(batch.getValue()).containsEntry(patient.getId(), Map.of(hospitalA.getId().toString(), 2L));
    }

    @Test
    void aVerifiedSuperAdminInGlobalViewReadsTheTrendThroughTheSameQuery() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
        LabResult atA = row(hospitalA, null, 1);
        when(labResultRepository.findById(atA.getId())).thenReturn(Optional.of(atA));
        when(labResultMapper.toResponseDTO(atA)).thenReturn(LabResultResponseDTO.builder().id("atA").build());
        when(labResultRepository.findTrendReadableAt(any(), any(), any(), any(), eq(true), any()))
            .thenReturn(List.of(atA));

        LabResultResponseDTO response = service.getLabResultById(atA.getId(), Locale.ENGLISH);

        assertThat(response.getTrendHistory()).hasSize(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> readable = ArgumentCaptor.forClass(Collection.class);
        verify(labResultRepository).findTrendReadableAt(eq(patient.getId()), eq(test.getId()),
            readable.capture(), org.mockito.ArgumentMatchers.isNull(), eq(true), any());
        // Never an empty IN list, and the filler names no hospital.
        assertThat(readable.getValue()).isNotEmpty()
            .doesNotContain(hospitalA.getId(), hospitalB.getId(), hospitalT.getId());
    }

    /** A result for the patient and test, ordered at {@code ordering}, sent to {@code performing} when not null. */
    private LabResult row(Hospital ordering, Hospital performing, int daysAgo) {
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setHospital(ordering);
        order.setPerformingHospital(performing);
        order.setPatient(patient);
        order.setLabTestDefinition(test);
        LabResult result = LabResult.builder()
            .labOrder(order)
            .resultValue(String.valueOf(10 + daysAgo))
            .resultDate(LocalDateTime.now().minusDays(daysAgo))
            .build();
        result.setId(UUID.randomUUID());
        return result;
    }

    private static Hospital hospital() {
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        return hospital;
    }
}
