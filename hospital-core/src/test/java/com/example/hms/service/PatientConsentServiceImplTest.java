package com.example.hms.service;

import com.example.hms.mapper.HospitalMapper;
import com.example.hms.mapper.PatientConsentMapper;
import com.example.hms.mapper.PatientMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientConsent;
import com.example.hms.payload.dto.HospitalResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.repository.PatientConsentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * E9 #65 — the service is read-only: two listings over the rows granted
 * before the consent-grant model was retired. The grant / revoke / active
 * cases went with the methods.
 */
@ExtendWith(MockitoExtension.class)
class PatientConsentServiceImplTest {

    @Mock private PatientConsentRepository consentRepository;
    @Mock private PatientConsentMapper consentMapper;
    @Mock private PatientMapper patientMapper;
    @Mock private HospitalMapper hospitalMapper;

    @InjectMocks private PatientConsentServiceImpl service;

    private static PatientConsent consentFor(Patient patient) {
        Hospital fromHosp = Hospital.builder().build();
        fromHosp.setId(UUID.randomUUID());
        Hospital toHosp = Hospital.builder().build();
        toHosp.setId(UUID.randomUUID());
        PatientConsent consent = PatientConsent.builder()
            .patient(patient).fromHospital(fromHosp).toHospital(toHosp).build();
        consent.setId(UUID.randomUUID());
        return consent;
    }

    private void stubMappers(Patient patient) {
        when(patientMapper.toPatientDTO(eq(patient), any(UUID.class), eq(true), eq(true)))
            .thenReturn(new PatientResponseDTO());
        when(hospitalMapper.toHospitalDTO(any(Hospital.class))).thenReturn(HospitalResponseDTO.builder().build());
        when(consentMapper.toDto(any(PatientConsent.class), any(), any(), any()))
            .thenReturn(PatientConsentResponseDTO.builder().build());
    }

    @Test
    void getAllConsents_returnsPage() {
        Patient patient = Patient.builder().build();
        patient.setId(UUID.randomUUID());
        Page<PatientConsent> page = new PageImpl<>(List.of(consentFor(patient)));
        when(consentRepository.findAll(any(Pageable.class))).thenReturn(page);
        stubMappers(patient);

        Page<PatientConsentResponseDTO> result = service.getAllConsents(PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getConsentsByPatient_returnsPage() {
        UUID patientId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        Page<PatientConsent> page = new PageImpl<>(List.of(consentFor(patient)));
        when(consentRepository.findAllByPatientId(eq(patientId), any(Pageable.class))).thenReturn(page);
        stubMappers(patient);

        Page<PatientConsentResponseDTO> result = service.getConsentsByPatient(patientId, PageRequest.of(0, 10));

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void getConsentsByPatient_empty_returnsEmptyPage() {
        UUID patientId = UUID.randomUUID();
        when(consentRepository.findAllByPatientId(eq(patientId), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        Page<PatientConsentResponseDTO> result = service.getConsentsByPatient(patientId, PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }
}
