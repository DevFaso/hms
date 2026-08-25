package com.example.hms.service;

import com.example.hms.enums.IsolationPrecautionType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.IsolationMapper;
import com.example.hms.model.Admission;
import com.example.hms.model.Hospital;
import com.example.hms.model.IsolationPrecaution;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.isolation.DiscontinuePrecautionRequestDTO;
import com.example.hms.payload.dto.isolation.IsolationPrecautionRequestDTO;
import com.example.hms.payload.dto.isolation.IsolationPrecautionResponseDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.IsolationPrecautionRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.impl.IsolationServiceImpl;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Isolation precautions (Tier 2 item 32).
 *
 * <p>Two things are pinned hardest. That CONCURRENT PRECAUTIONS ARE POSSIBLE
 * but duplicates of one type are not — a viral haemorrhagic fever is contact
 * AND droplet, while two nurses acting on the same result must not produce two
 * CONTACT rows. And that ONLY AIRBORNE CONSTRAINS PLACEMENT: treating
 * protective isolation like the others would send a neutropenic patient toward
 * an infectious ward, which is the failure inverted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IsolationServiceImplTest {

    @Spy private Clock clock = Clock.systemDefaultZone();
    @Mock private IsolationPrecautionRepository precautionRepository;
    @Mock private AdmissionRepository admissionRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private PatientChartAccess patientChartAccess;
    @Mock private RoleValidator roleValidator;
    @Spy private IsolationMapper mapper = new IsolationMapper();

    @InjectMocks private IsolationServiceImpl service;

    private UUID hospitalId;
    private UUID patientId;
    private Hospital hospital;
    private Patient patient;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        patientId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Aminata");
        patient.setLastName("Diallo");

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientChartAccess.require(any(), any())).thenReturn(patient);
        when(precautionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(precautionRepository.findActiveOfType(any(), any())).thenReturn(Optional.empty());
    }

    private IsolationPrecautionRequestDTO.IsolationPrecautionRequestDTOBuilder base() {
        return IsolationPrecautionRequestDTO.builder()
            .patientId(patientId)
            .precautionType(IsolationPrecautionType.CONTACT)
            .reason("Suspected cholera");
    }

    private IsolationPrecaution persisted(IsolationPrecautionType type) {
        IsolationPrecaution precaution = IsolationPrecaution.builder()
            .hospital(hospital)
            .patient(patient)
            .precautionType(type)
            .reason("Suspected cholera")
            .startedAt(LocalDateTime.now().minusDays(1))
            .build();
        precaution.setId(UUID.randomUUID());
        when(precautionRepository.findById(precaution.getId())).thenReturn(Optional.of(precaution));
        return precaution;
    }

    // ── Starting ────────────────────────────────────────────────────────

    @Test
    void startingAPrecautionRecordsItAsInForce() {
        IsolationPrecautionResponseDTO result = service.startPrecaution(base().build());

        assertThat(result.isActive()).isTrue();
        assertThat(result.getStartedAt()).isNotNull();
        assertThat(result.getPrecautionType()).isEqualTo(IsolationPrecautionType.CONTACT);
        assertThat(result.getPatientName()).isEqualTo("Aminata Diallo");
    }

    @Test
    void aDuplicateOfATypeAlreadyInForceIsRefused() {
        // Two nurses acting on the same result would otherwise produce two
        // CONTACT rows and a banner that double-counts.
        IsolationPrecaution existing = persisted(IsolationPrecautionType.CONTACT);
        when(precautionRepository.findActiveOfType(patientId, IsolationPrecautionType.CONTACT))
            .thenReturn(Optional.of(existing));
        IsolationPrecautionRequestDTO request = base().build();

        assertThatThrownBy(() -> service.startPrecaution(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already on CONTACT");
        verify(precautionRepository, never()).save(any());
    }

    @Test
    void aDifferentTypeCanRunAlongsideAnExistingOne() {
        // A viral haemorrhagic fever is contact AND droplet. Only a duplicate
        // of the SAME type is refused.
        IsolationPrecaution existing = persisted(IsolationPrecautionType.CONTACT);
        when(precautionRepository.findActiveOfType(patientId, IsolationPrecautionType.CONTACT))
            .thenReturn(Optional.of(existing));

        IsolationPrecautionResponseDTO result = service.startPrecaution(
            base().precautionType(IsolationPrecautionType.DROPLET).build());

        assertThat(result.getPrecautionType()).isEqualTo(IsolationPrecautionType.DROPLET);
        assertThat(result.isActive()).isTrue();
    }

    @Test
    void aPrecautionNeedsAReason() {
        IsolationPrecautionRequestDTO request = base().reason("   ").build();

        assertThatThrownBy(() -> service.startPrecaution(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reason is required");
    }

    @Test
    void aPrecautionCanStartBeforeThereIsAnAdmission() {
        // Precautions start in the emergency department, which is exactly when
        // they matter most: they decide which bed the patient may be given.
        IsolationPrecautionResponseDTO result = service.startPrecaution(base().admissionId(null).build());

        assertThat(result.getAdmissionId()).isNull();
        assertThat(result.isActive()).isTrue();
    }

    @Test
    void anAdmissionBelongingToAnotherPatientIsRefused() {
        Admission other = new Admission();
        other.setId(UUID.randomUUID());
        other.setHospital(hospital);
        Patient somebodyElse = new Patient();
        somebodyElse.setId(UUID.randomUUID());
        other.setPatient(somebodyElse);
        when(admissionRepository.findById(other.getId())).thenReturn(Optional.of(other));
        IsolationPrecautionRequestDTO request = base().admissionId(other.getId()).build();

        assertThatThrownBy(() -> service.startPrecaution(request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── The placement rule ──────────────────────────────────────────────

    @Test
    void airborneConstrainsWhereThePatientMayLie() {
        IsolationPrecautionResponseDTO result = service.startPrecaution(
            base().precautionType(IsolationPrecautionType.AIRBORNE).reason("Suspected TB").build());

        assertThat(result.isRequiresIsolationWard()).isTrue();
    }

    @Test
    void contactAndDropletAreManagedAtTheBedsideAndDoNotConstrainPlacement() {
        assertThat(service.startPrecaution(base().build()).isRequiresIsolationWard()).isFalse();
        assertThat(service.startPrecaution(
            base().precautionType(IsolationPrecautionType.DROPLET).build())
            .isRequiresIsolationWard()).isFalse();
    }

    @Test
    void protectiveIsolationProtectsThePatientAndIsNotAPlacementConstraint() {
        // The direction is inverted: it shields the patient FROM the ward. A
        // rule that treats it like airborne sends a neutropenic patient toward
        // the infectious cases.
        IsolationPrecautionResponseDTO result = service.startPrecaution(
            base().precautionType(IsolationPrecautionType.PROTECTIVE).reason("Neutropenic").build());

        assertThat(result.isRequiresIsolationWard()).isFalse();
    }

    // ── Discontinuing ───────────────────────────────────────────────────

    @Test
    void discontinuingKeepsTheRowAsHistory() {
        IsolationPrecaution precaution = persisted(IsolationPrecautionType.CONTACT);

        IsolationPrecautionResponseDTO result = service.discontinuePrecaution(precaution.getId(),
            DiscontinuePrecautionRequestDTO.builder()
                .discontinuationReason("Stool culture negative").build());

        assertThat(result.isActive()).isFalse();
        assertThat(result.getEndedAt()).isNotNull();
        assertThat(result.getDiscontinuationReason()).isEqualTo("Stool culture negative");
        // The row survives — contact tracing asks what WAS in force.
        assertThat(result.getStartedAt()).isNotNull();
        verify(precautionRepository, never()).delete(any());
    }

    @Test
    void liftingAPrecautionNeedsAReason() {
        UUID precautionId = persisted(IsolationPrecautionType.CONTACT).getId();
        DiscontinuePrecautionRequestDTO blank =
            DiscontinuePrecautionRequestDTO.builder().discontinuationReason("  ").build();

        assertThatThrownBy(() -> service.discontinuePrecaution(precautionId, blank))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reason is required");
    }

    @Test
    void aPrecautionCannotBeDiscontinuedTwice() {
        IsolationPrecaution precaution = persisted(IsolationPrecautionType.CONTACT);
        precaution.setEndedAt(LocalDateTime.now().minusHours(1));
        UUID precautionId = precaution.getId();
        DiscontinuePrecautionRequestDTO request =
            DiscontinuePrecautionRequestDTO.builder().discontinuationReason("Again").build();

        assertThatThrownBy(() -> service.discontinuePrecaution(precautionId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already been discontinued");
    }

    // ── Reads and tenancy ───────────────────────────────────────────────

    @Test
    void theActiveListIsWhatIsInForceNow() {
        IsolationPrecaution airborne = persisted(IsolationPrecautionType.AIRBORNE);
        when(precautionRepository.findActiveForPatient(patientId)).thenReturn(List.of(airborne));

        List<IsolationPrecautionResponseDTO> active = service.getActiveForPatient(patientId);

        assertThat(active).hasSize(1);
        assertThat(active.get(0).isRequiresIsolationWard()).isTrue();
    }

    @Test
    void theHistoryIncludesPrecautionsAlreadyLifted() {
        IsolationPrecaution lifted = persisted(IsolationPrecautionType.DROPLET);
        lifted.setEndedAt(LocalDateTime.now().minusHours(2));
        when(precautionRepository.findAllForPatient(patientId)).thenReturn(List.of(lifted));

        List<IsolationPrecautionResponseDTO> history = service.getHistoryForPatient(patientId);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).isActive()).isFalse();
    }

    @Test
    void aPrecautionAtAnotherHospitalIsNotFoundRatherThanForbidden() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        IsolationPrecaution foreign = IsolationPrecaution.builder().hospital(other).build();
        UUID id = UUID.randomUUID();
        foreign.setId(id);
        when(precautionRepository.findById(id)).thenReturn(Optional.of(foreign));
        DiscontinuePrecautionRequestDTO request =
            DiscontinuePrecautionRequestDTO.builder().discontinuationReason("Not mine").build();

        assertThatThrownBy(() -> service.discontinuePrecaution(id, request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aSuperAdminWithNoActiveHospitalCannotRaiseAPrecaution() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        IsolationPrecautionRequestDTO request = base().build();

        assertThatThrownBy(() -> service.startPrecaution(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital is required");
    }

    @Test
    void aStaffMemberFromAnotherHospitalCannotBeNamedAsOrderingTheAlert() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        Staff foreign = new Staff();
        foreign.setId(UUID.randomUUID());
        foreign.setHospital(other);
        when(staffRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        IsolationPrecautionRequestDTO request = base().orderedByStaffId(foreign.getId()).build();

        assertThatThrownBy(() -> service.startPrecaution(request))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
