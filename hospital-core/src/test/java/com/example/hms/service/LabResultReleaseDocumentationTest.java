package com.example.hms.service;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabQcEvent;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.LabResultReleaseRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * r63 D2304 / D2305: what a release must document.
 *
 * <ul>
 *   <li>A CRITICAL result is released only with who was told, when and the
 *       read-back, stamped with who released it.</li>
 *   <li>While the test's latest QC run is rejected, a result is released only
 *       with an override reason, stamped with the run, who and when.</li>
 * </ul>
 */
@ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
@ExtendWith(MockitoExtension.class)
class LabResultReleaseDocumentationTest {

    @Mock private com.example.hms.repository.LabSpecimenRepository labSpecimenRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private LabResultMapper labResultMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private AuthService authService;
    @Mock private UserRepository userRepository;
    @Mock private InstrumentOutboxService instrumentOutboxService;
    @Mock private com.example.hms.service.lab.LabOrderRoutingNotifier routingNotifier;
    @Mock private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;
    @Mock private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private CriticalValueNotificationService criticalValueNotificationService;
    @Mock private LabQcReleaseGate qcReleaseGate;

    @InjectMocks
    private LabResultServiceImpl service;

    private UUID hospitalId;
    private UUID actorId;
    private LabResult result;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        LabTestDefinition test = new LabTestDefinition();
        test.setId(UUID.randomUUID());
        test.setName("Kaliemie");
        test.setTestCode("K");
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setHospital(hospital);
        order.setPatient(patient);
        order.setLabTestDefinition(test);
        UserRoleHospitalAssignment author = new UserRoleHospitalAssignment();
        author.setId(UUID.randomUUID());
        author.setHospital(hospital);
        User authorUser = new User();
        authorUser.setId(UUID.randomUUID());
        author.setUser(authorUser);

        result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setAssignment(author);
        result.setResultValue("6.9");
        result.setResultUnit("mmol/L");
        result.setResultDate(LocalDateTime.now().minusHours(1));

        lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        lenient().when(labResultRepository.findById(result.getId())).thenReturn(Optional.of(result));
        lenient().when(authService.getCurrentUserId()).thenReturn(actorId);
        lenient().when(roleValidator.isLabScientist(any(), any())).thenReturn(true);
        lenient().when(labResultRepository.save(result)).thenReturn(result);
    }

    private void gradedAs(String flag) {
        lenient().when(labResultMapper.toResponseDTO(result))
            .thenReturn(LabResultResponseDTO.builder().severityFlag(flag).build());
        lenient().when(labResultMapper.raisesCriticalAlert(flag)).thenReturn("CRITICAL".equals(flag));
    }

    @Test
    void aCriticalResultIsNotReleasedWithoutTheCommunicationAndEveryMissingFieldIsNamed() {
        gradedAs("CRITICAL");

        assertThatThrownBy(() -> service.releaseLabResult(result.getId(), false, false, null, Locale.ENGLISH))
            .isInstanceOfSatisfying(FieldBusinessException.class, ex -> assertThat(ex.getFieldErrors())
                .containsOnlyKeys("criticalCommunicatedTo", "criticalCommunicatedAt", "criticalReadBack"));
        assertThat(result.isReleased()).isFalse();
        verify(labResultRepository, never()).save(any());
    }

    @Test
    void aCriticalResultReleasedWithTheCommunicationRecordsWhoWhenAndTheReadBack() {
        gradedAs("CRITICAL");
        LocalDateTime calledAt = LocalDateTime.now().minusMinutes(3);

        service.releaseLabResult(result.getId(), false, false, LabResultReleaseRequestDTO.builder()
            .criticalCommunicatedTo(" Dr Ouedraogo (garde) ")
            .criticalCommunicatedAt(calledAt)
            .criticalReadBack("K 6,9")
            .build(), Locale.ENGLISH);

        assertThat(result.isReleased()).isTrue();
        assertThat(result.getCriticalCommunicatedTo()).isEqualTo("Dr Ouedraogo (garde)");
        assertThat(result.getCriticalCommunicatedAt()).isEqualTo(calledAt);
        assertThat(result.getCriticalCommunicationReadBack()).isEqualTo("K 6,9");
        assertThat(result.getCriticalCommunicatedByUserId()).isEqualTo(actorId);
    }

    @Test
    void aCommunicationTimeInTheFutureIsRefused() {
        gradedAs("CRITICAL");

        assertThatThrownBy(() -> service.releaseLabResult(result.getId(), false, false,
            LabResultReleaseRequestDTO.builder()
                .criticalCommunicatedTo("Dr X")
                .criticalCommunicatedAt(LocalDateTime.now().plusHours(2))
                .criticalReadBack("6.9")
                .build(), Locale.ENGLISH))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                ex -> assertThat(ex.getField()).isEqualTo("criticalCommunicatedAt"));
        assertThat(result.isReleased()).isFalse();
    }

    @Test
    void aNormalResultNeedsNoCommunication() {
        gradedAs("NORMAL");

        service.releaseLabResult(result.getId(), false, false, null, Locale.ENGLISH);

        assertThat(result.isReleased()).isTrue();
        assertThat(result.getCriticalCommunicatedTo()).isNull();
    }

    @Test
    void aRejectedQcRunHoldsTheReleaseUntilAReasonIsGiven() {
        gradedAs("NORMAL");
        LabQcEvent rejected = LabQcEvent.builder().passed(false).build();
        rejected.setId(UUID.randomUUID());
        when(qcReleaseGate.blockingRun(result.getLabOrder())).thenReturn(Optional.of(rejected));

        assertThatThrownBy(() -> service.releaseLabResult(result.getId(), false, false,
            LabResultReleaseRequestDTO.builder().qcOverrideReason("   ").build(), Locale.ENGLISH))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                ex -> assertThat(ex.getField()).isEqualTo("qcOverrideReason"));
        assertThat(result.isReleased()).isFalse();
        verify(labResultRepository, never()).save(any());
    }

    @Test
    void rejectedQcCannotBeOverriddenWithAShortReason() {
        gradedAs("NORMAL");
        LabQcEvent rejected = LabQcEvent.builder().passed(false).build();
        rejected.setId(UUID.randomUUID());
        when(qcReleaseGate.blockingRun(result.getLabOrder())).thenReturn(Optional.of(rejected));
        for (String reason : java.util.List.of("ok", "          ", "123456789")) {
            assertThatThrownBy(() -> service.releaseLabResult(result.getId(), false, false,
                LabResultReleaseRequestDTO.builder().qcOverrideReason(reason).build(), Locale.ENGLISH))
                .isInstanceOfSatisfying(FieldBusinessException.class,
                    ex -> assertThat(ex.getField()).isEqualTo("qcOverrideReason"));
        }
        assertThat(result.isReleased()).isFalse();
        verify(labResultRepository, never()).save(any());
    }

    @Test
    void aDocumentedOverrideReleasesAndRecordsTheRunWhoAndWhy() {
        gradedAs("NORMAL");
        LabQcEvent rejected = LabQcEvent.builder().passed(false).build();
        rejected.setId(UUID.randomUUID());
        when(qcReleaseGate.blockingRun(result.getLabOrder())).thenReturn(Optional.of(rejected));

        service.releaseLabResult(result.getId(), false, false,
            LabResultReleaseRequestDTO.builder().qcOverrideReason("Rerun on backup analyser, in control").build(),
            Locale.ENGLISH);

        assertThat(result.isReleased()).isTrue();
        assertThat(result.getQcOverrideReason()).isEqualTo("Rerun on backup analyser, in control");
        assertThat(result.getQcOverrideEventId()).isEqualTo(rejected.getId());
        assertThat(result.getQcOverrideByUserId()).isEqualTo(actorId);
        assertThat(result.getQcOverrideAt()).isNotNull();
    }

    /**
     * QA D3900: potassium 2,1 was released with « Valeur relue » 9,9. The call log's read-back is
     * compared with the result as the clinician's is; a mismatch is refused, named on its field,
     * and nothing is released or stored.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"9,9", "9.9", "K 9,9", "6,9 g/L", "69", "abc", "Na 6,9"})
    void aCallLogReadBackThatDiffersFromTheResultIsRefused_D3900(String readBack) {
        gradedAs("CRITICAL");

        assertThatThrownBy(() -> service.releaseLabResult(result.getId(), false, false,
            LabResultReleaseRequestDTO.builder()
                .criticalCommunicatedTo("Dr Ouedraogo")
                .criticalCommunicatedAt(LocalDateTime.now().minusMinutes(2))
                .criticalReadBack(readBack)
                .build(), Locale.ENGLISH))
            .isInstanceOfSatisfying(FieldBusinessException.class, ex -> {
                assertThat(ex.getField()).isEqualTo("criticalReadBack");
                assertThat(ex.getMessageKey()).isEqualTo("lab.result.critical.readBackMismatch");
            });
        assertThat(result.isReleased()).isFalse();
        assertThat(result.getCriticalCommunicationReadBack()).isNull();
        verify(labResultRepository, never()).save(any());
    }

    /** QA D3900: the value as people say and type it: comma or point, trailing zero, unit, the test's own name or code. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "6,9", "6.9", "6,90", "6,9 mmol/L", "6.9 MMOL/L", "K 6,9", "Kaliémie : 6,9 mmol/L", "K = 6.9"})
    void aCallLogReadBackOfTheResultIsAccepted_D3900(String readBack) {
        gradedAs("CRITICAL");

        service.releaseLabResult(result.getId(), false, false, LabResultReleaseRequestDTO.builder()
            .criticalCommunicatedTo("Dr Ouedraogo")
            .criticalCommunicatedAt(LocalDateTime.now().minusMinutes(2))
            .criticalReadBack(readBack)
            .build(), Locale.ENGLISH);

        assertThat(result.isReleased()).isTrue();
        assertThat(result.getCriticalCommunicationReadBack()).isEqualTo(readBack);
    }

    @Test
    void aTextResultsReadBackMustBeTheSameText_D3900() {
        gradedAs("CRITICAL");
        result.setResultValue("Positif");
        result.setResultUnit(null);
        LabResultReleaseRequestDTO.LabResultReleaseRequestDTOBuilder call = LabResultReleaseRequestDTO.builder()
            .criticalCommunicatedTo("Dr Ouedraogo")
            .criticalCommunicatedAt(LocalDateTime.now().minusMinutes(2));

        LabResultReleaseRequestDTO wrong = call.criticalReadBack("Négatif").build();
        UUID id = result.getId();
        assertThatThrownBy(() -> service.releaseLabResult(id, false, false, wrong, Locale.ENGLISH))
            .isInstanceOf(FieldBusinessException.class);

        service.releaseLabResult(result.getId(), false, false, call.criticalReadBack("positif").build(), Locale.ENGLISH);
        assertThat(result.isReleased()).isTrue();
    }
}
