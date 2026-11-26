package com.example.hms.mapper;

import com.example.hms.model.Patient;
import com.example.hms.payload.dto.BloodGroupStatusDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.service.transfusion.PatientBloodGroupStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PatientMapperBatchTest {
    private final PatientBloodGroupStatusService groups = mock(PatientBloodGroupStatusService.class);
    private final PatientMapper mapper = new PatientMapper(new StaticMessageSource());

    @Test
    void batchPreservesOrderAndUsesOnlyTheRequestedHospitalWithoutPerPatientReads() {
        Patient first = patient("A+");
        Patient second = patient("O-");
        UUID hospital = UUID.randomUUID();
        BloodGroupStatusDTO confirmed = BloodGroupStatusDTO.builder()
            .declared("A+").confirmed("B+").discrepancy(true).build();
        mapper.setBloodGroupStatus(groups);
        when(groups.ofAll(List.of(first, second), hospital)).thenReturn(Map.of(first.getId(), confirmed));
        List<PatientResponseDTO> mapped = mapper.toPatientDTOs(List.of(first, second), hospital);
        assertThat(mapped).extracting(PatientResponseDTO::getId).containsExactly(first.getId(), second.getId());
        assertThat(mapped.getFirst().getBloodGroup()).isSameAs(confirmed);
        assertThat(mapped.getLast().getBloodGroup().getDeclared()).isEqualTo("O-");
        assertThat(mapped.getLast().getBloodGroup().getConfirmed()).isNull();
        verify(groups).ofAll(List.of(first, second), hospital);
        verifyNoMoreInteractions(groups);
    }

    @Test
    void unpagedListsUseBoundedBatchesWithoutDroppingRows() {
        List<Patient> patients = java.util.stream.IntStream.range(0, 1_001)
            .mapToObj(index -> patient("A+")).toList();
        UUID hospital = UUID.randomUUID();
        mapper.setBloodGroupStatus(groups);
        when(groups.ofAll(patients.subList(0, 1_000), hospital)).thenReturn(Map.of());
        when(groups.ofAll(patients.subList(1_000, 1_001), hospital)).thenReturn(Map.of());
        assertThat(mapper.toPatientDTOs(patients, hospital)).extracting(PatientResponseDTO::getId)
            .containsExactlyElementsOf(patients.stream().map(Patient::getId).toList());
        verify(groups).ofAll(patients.subList(0, 1_000), hospital);
        verify(groups).ofAll(patients.subList(1_000, 1_001), hospital);
        verifyNoMoreInteractions(groups);
    }

    @Test
    void handBuiltMapperAndTransientPatientKeepDeclaredStatus() {
        Patient transientPatient = patient("A-");
        transientPatient.setId(null);
        assertThat(mapper.toPatientDTOs(List.of(transientPatient), null).getFirst()
            .getBloodGroup().getDeclared()).isEqualTo("A-");
        assertThat(mapper.toPatientDTOs(List.of(), null)).isEmpty();
    }

    @Test
    void singlePatientStillUsesTheScopedSingleRead() {
        Patient patient = patient("A+");
        UUID hospital = UUID.randomUUID();
        BloodGroupStatusDTO confirmed = BloodGroupStatusDTO.builder().confirmed("A+").build();
        mapper.setBloodGroupStatus(groups);
        when(groups.of(patient, hospital)).thenReturn(confirmed);
        assertThat(mapper.toPatientDTO(patient, hospital).getBloodGroup()).isSameAs(confirmed);
        verify(groups).of(patient, hospital);
        verifyNoMoreInteractions(groups);
    }

    private static Patient patient(String declared) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Synthetic");
        patient.setLastName("Patient");
        patient.setBloodType(declared);
        return patient;
    }
}
