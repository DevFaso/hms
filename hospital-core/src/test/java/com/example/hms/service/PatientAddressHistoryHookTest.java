package com.example.hms.service;

import com.example.hms.model.Patient;
import com.example.hms.model.PatientAddressHistory;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PatientProfileUpdateRequestDTO;
import com.example.hms.payload.dto.PatientRequestDTO;
import com.example.hms.repository.PatientAddressHistoryRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.mapper.PatientMapper;
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

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tier 2 item 38 — the address-history hooks. The contract worth pinning:
 * a real move records the OLD address; a first fill-in of a blank address
 * and a re-statement of the same address record NOTHING (history rows mean
 * "the patient moved", not "someone pressed save").
 *
 * <p>LENIENT strictness: PatientServiceImpl has ~20 collaborators and these
 * tests exercise one seam through two long methods — pinning every
 * incidental stub would drown the contract being tested.
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
    private Patient patient;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Awa");
        patient.setLastName("Traore");
        patient.setAddressLine1("Secteur 4, Rue 12");
        patient.setCity("Bobo-Dioulasso");
        patient.setCountry("Burkina Faso");
        patient.setAddress("Secteur 4, Rue 12, Bobo-Dioulasso, Burkina Faso");

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
        // the same lines — pressing save is not a move.
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
}
