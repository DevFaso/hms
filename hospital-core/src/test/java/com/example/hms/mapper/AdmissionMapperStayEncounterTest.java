package com.example.hms.mapper;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AdmissionResponseDTO;
import com.example.hms.repository.EncounterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D98/D135: the admission response carries the stay's own encounter. */
@ExtendWith(MockitoExtension.class)
class AdmissionMapperStayEncounterTest {

    @Mock private EncounterRepository encounterRepository;

    private AdmissionMapper mapper;
    private Admission admission;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        mapper = new AdmissionMapper(encounterRepository);
        patient = new Patient();
        patient.setId(UUID.randomUUID());
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        admission = new Admission();
        admission.setId(UUID.randomUUID());
        admission.setPatient(patient);
        admission.setHospital(hospital);
    }

    private Encounter stay(EncounterStatus status) {
        Encounter enc = new Encounter();
        enc.setId(UUID.randomUUID());
        enc.setStatus(status);
        enc.setEncounterDate(LocalDateTime.now());
        return enc;
    }

    @Test
    void anOpenStay_isBothTheEncounterAndTheCurrentEncounter() {
        Encounter stay = stay(EncounterStatus.IN_PROGRESS);
        stay.setCode("ENC-STAY-1");
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .thenReturn(Optional.of(stay));

        AdmissionResponseDTO dto = mapper.toResponseDTO(admission);

        assertThat(dto.getEncounterId()).isEqualTo(stay.getId());
        assertThat(dto.getCurrentEncounterId()).isEqualTo(stay.getId());
        // QA D219: the stay's readable code travels with the admission.
        assertThat(dto.getEncounterCode()).isEqualTo("ENC-STAY-1");
        verify(encounterRepository, never()).findByPatient_IdAndHospital_IdAndStatusNotIn(any(), any(), any());
    }

    @Test
    void aDischargedStay_keepsItsEncounter_butHasNoCurrentOne() {
        Encounter stay = stay(EncounterStatus.COMPLETED);
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .thenReturn(Optional.of(stay));

        AdmissionResponseDTO dto = mapper.toResponseDTO(admission);

        assertThat(dto.getEncounterId()).isEqualTo(stay.getId());
        assertThat(dto.getCurrentEncounterId()).isNull();
    }

    @Test
    void anAdmissionFromBeforeV193_fallsBackToThePatientsOpenEncounter() {
        Encounter open = stay(EncounterStatus.IN_PROGRESS);
        when(encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()))
            .thenReturn(Optional.empty());
        when(encounterRepository.findByPatient_IdAndHospital_IdAndStatusNotIn(
                eq(patient.getId()), eq(hospital.getId()), any()))
            .thenReturn(List.of(open));

        AdmissionResponseDTO dto = mapper.toResponseDTO(admission);

        assertThat(dto.getEncounterId()).isNull();
        assertThat(dto.getCurrentEncounterId()).isEqualTo(open.getId());
    }
}
