package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAddressHistory;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PatientProfileUpdateRequestDTO;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.repository.PatientAddressHistoryRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.mapper.PatientMapper;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tier 2 item 38 — the address-history hooks and the PHI read path. The
 * contract worth pinning: a real move records the OLD address; a first
 * fill-in of a blank address and a re-statement of the same address record
 * NOTHING; a legacy address-only patient (no component fields) still
 * records when the composed line changes; and the read collapses foreign
 * and nonexistent patients into the IDENTICAL not-found (no existence
 * oracle).
 *
 * <p>LENIENT strictness: PatientServiceImpl has ~20 collaborators and these
 * tests exercise one seam through two long methods — pinning every
 * incidental stub would drown the contract being tested. The recorder is
 * REAL (wrapping the mocked repository), because the recorder's comparison
 * rules are exactly what these tests exist to pin.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatientAddressHistoryHookTest {

    @Mock private PatientRepository patientRepository;
    @Mock private PatientAddressHistoryRepository addressHistoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private PatientMapper patientMapper;

    @InjectMocks
    private PatientServiceImpl patientService;

    private UUID patientId;
    private UUID hospitalId;
    private Patient patient;

    @BeforeEach
    void setUp() {
        // Real recorder over the mocked repo — @InjectMocks would hand the
        // service a mock recorder and these tests would pin nothing.
        PatientAddressHistoryRecorder recorder = new PatientAddressHistoryRecorder(
            addressHistoryRepository, mock(RoleValidator.class), mock(AuditEventLogService.class));
        ReflectionTestUtils.setField(patientService, "addressHistoryRecorder", recorder);

        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Awa");
        patient.setLastName("Traore");
        patient.setAddressLine1("Secteur 4, Rue 12");
        patient.setCity("Bobo-Dioulasso");
        patient.setCountry("Burkina Faso");
        patient.setAddress("Secteur 4, Rue 12, Bobo-Dioulasso, Burkina Faso");
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        PatientHospitalRegistration reg = new PatientHospitalRegistration();
        reg.setHospital(hospital);
        reg.setActive(true);
        Set<PatientHospitalRegistration> regs = new HashSet<>();
        regs.add(reg);
        patient.setHospitalRegistrations(regs);

        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(patientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("patchPatient with a NEW address records the OLD one as history")
    void patchRecordsTheSupersededAddress() {
        PatientProfileUpdateRequestDTO request = new PatientProfileUpdateRequestDTO();
        request.setAddressLine1("Secteur 22, Avenue de la Nation");
        request.setCity("Ouagadougou");

        patientService.patchPatient(patientId, request, null, Locale.FRENCH);

        ArgumentCaptor<PatientAddressHistory> captor =
            ArgumentCaptor.forClass(PatientAddressHistory.class);
        verify(addressHistoryRepository).save(captor.capture());
        PatientAddressHistory history = captor.getValue();
        assertThat(history.getAddress()).contains("Secteur 4, Rue 12");
        assertThat(history.getCity()).isEqualTo("Bobo-Dioulasso");
        assertThat(history.getPatient()).isSameAs(patient);
        // The CURRENT address on the patient is the new one.
        assertThat(patient.getCity()).isEqualTo("Ouagadougou");
    }

    @Test
    @DisplayName("re-stating the same address records nothing")
    void restatingTheSameAddressRecordsNothing() {
        PatientProfileUpdateRequestDTO request = new PatientProfileUpdateRequestDTO();
        request.setAddressLine1("Secteur 4, Rue 12");
        request.setCity("Bobo-Dioulasso");

        patientService.patchPatient(patientId, request, null, Locale.FRENCH);

        // buildMailingAddress recomposes, but the snapshot comparison sees
        // the same components — pressing save is not a move.
        verify(addressHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("the FIRST fill-in of a blank address is not a move — nothing recorded")
    void firstFillInRecordsNothing() {
        patient.setAddress(null);
        patient.setAddressLine1(null);
        patient.setCity(null);
        patient.setCountry(null);
        PatientProfileUpdateRequestDTO request = new PatientProfileUpdateRequestDTO();
        request.setAddressLine1("Secteur 9");
        request.setCity("Koudougou");

        patientService.patchPatient(patientId, request, null, Locale.FRENCH);

        verify(addressHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("a LEGACY address-only patient (no component fields) still records a move")
    void legacyAddressOnlyChangeRecords() {
        // Old rows carry only the composed line; ignoring it entirely would
        // let these patients move forever without a single history row
        // (PR #550 review High).
        patient.setAddressLine1(null);
        patient.setCity(null);
        patient.setCountry(null);
        patient.setAddress("Ancien quartier, Ouahigouya");
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User()));
        doAnswer(inv -> {
            Patient target = inv.getArgument(1);
            target.setAddress("Nouveau quartier, Ouahigouya");
            return null;
        }).when(patientMapper).updatePatientFromDto(any(), any(), any());
        PatientRequestDTO dto = new PatientRequestDTO();
        dto.setUserId(userId);

        patientService.updatePatient(patientId, dto, Locale.FRENCH);

        ArgumentCaptor<PatientAddressHistory> captor =
            ArgumentCaptor.forClass(PatientAddressHistory.class);
        verify(addressHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getAddress()).isEqualTo("Ancien quartier, Ouahigouya");
    }

    @Test
    @DisplayName("patchPatient accepts self-reported ethnicity")
    void patchAcceptsEthnicity() {
        PatientProfileUpdateRequestDTO request = new PatientProfileUpdateRequestDTO();
        request.setEthnicity("Mossi");

        patientService.patchPatient(patientId, request, null, Locale.FRENCH);

        assertThat(patient.getEthnicity()).isEqualTo("Mossi");
        verify(patientRepository).save(patient);
    }

    @Test
    @DisplayName("the full-form update path records a move through the mapper too")
    void updatePatientRecordsMoveThroughTheMapperPath() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(new User()));
        // The mocked mapper mutates the entity the way the real one would.
        doAnswer(inv -> {
            Patient target = inv.getArgument(1);
            target.setAddressLine1("Secteur 30");
            target.setCity("Ouagadougou");
            return null;
        }).when(patientMapper).updatePatientFromDto(any(), any(), any());

        PatientRequestDTO dto = new PatientRequestDTO();
        dto.setUserId(userId);

        patientService.updatePatient(patientId, dto, Locale.FRENCH);

        ArgumentCaptor<PatientAddressHistory> captor =
            ArgumentCaptor.forClass(PatientAddressHistory.class);
        verify(addressHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getCity()).isEqualTo("Bobo-Dioulasso");
    }

    // ── the PHI read path ───────────────────────────────────────────────

    @Test
    @DisplayName("getAddressHistory maps rows newest-first with replacedAt = createdAt")
    void addressHistoryReadMapsRows() {
        PatientAddressHistory row = PatientAddressHistory.builder()
            .patient(patient)
            .address("Ancien quartier")
            .city("Bobo-Dioulasso")
            .country("Burkina Faso")
            .build();
        row.setId(UUID.randomUUID());
        row.setCreatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        when(addressHistoryRepository.findByPatient_IdOrderByCreatedAtDesc(patientId))
            .thenReturn(List.of(row));

        var rows = patientService.getAddressHistory(patientId, hospitalId);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAddress()).isEqualTo("Ancien quartier");
        assertThat(rows.get(0).getReplacedAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 10, 0));
    }

    @Test
    @DisplayName("foreign and NONEXISTENT patients collapse to the identical not-found — no existence oracle")
    void addressHistoryReadCollapsesForeignAndUnknownAlike() {
        UUID unknownId = UUID.randomUUID();
        when(patientRepository.findByIdUnscoped(unknownId)).thenReturn(Optional.empty());
        UUID foreignHospital = UUID.randomUUID();

        Throwable unknown = org.assertj.core.api.Assertions.catchThrowable(
            () -> patientService.getAddressHistory(unknownId, hospitalId));
        Throwable foreign = org.assertj.core.api.Assertions.catchThrowable(
            () -> patientService.getAddressHistory(patientId, foreignHospital));

        assertThat(unknown).isInstanceOf(ResourceNotFoundException.class);
        assertThat(foreign).isInstanceOf(ResourceNotFoundException.class);
        // The messages differ only by the probed id — a caller cannot tell
        // "exists elsewhere" from "does not exist".
        assertThat(foreign.getMessage().replace(patientId.toString(), "<id>"))
            .isEqualTo(unknown.getMessage().replace(unknownId.toString(), "<id>"));
        verify(addressHistoryRepository, never()).findByPatient_IdOrderByCreatedAtDesc(any());
    }
}
