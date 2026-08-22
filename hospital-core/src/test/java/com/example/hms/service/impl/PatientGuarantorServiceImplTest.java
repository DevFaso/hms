package com.example.hms.service.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientGuarantor;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.GuarantorRequestDTO;
import com.example.hms.payload.dto.GuarantorResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientGuarantorRepository;
import com.example.hms.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Guarantor accounts (P3 #21): the first payer-other-than-patient concept
 * beyond insurance subscriber fields. Deactivate-never-delete; one primary
 * per patient+hospital.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatientGuarantorServiceImplTest {

    @Mock private PatientGuarantorRepository guarantorRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;

    @InjectMocks private PatientGuarantorServiceImpl service;

    private UUID patientId;
    private UUID hospitalId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);

        patientId = UUID.randomUUID();
        patient = Patient.builder().firstName("Awa").lastName("Kaboré").build();
        patient.setId(patientId);
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setHospital(hospital);
        registration.setActive(true);
        patient.setHospitalRegistrations(Set.of(registration));

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(guarantorRepository.save(any(PatientGuarantor.class))).thenAnswer(i -> i.getArgument(0));
        when(guarantorRepository.findByPatient_IdAndHospital_IdAndActiveTrue(any(), any()))
            .thenReturn(List.of());
    }

    private GuarantorRequestDTO request(String name, Boolean primary) {
        return GuarantorRequestDTO.builder()
            .fullName(name)
            .relationship("Mother")
            .phone("+22670000000")
            .primary(primary)
            .build();
    }

    private PatientGuarantor stored(boolean primary, boolean active) {
        PatientGuarantor guarantor = PatientGuarantor.builder()
            .patient(patient).hospital(hospital)
            .fullName("Mariam Kaboré")
            .primary(primary)
            .active(active)
            .build();
        guarantor.setId(UUID.randomUUID());
        return guarantor;
    }

    @Test
    void addStoresTheGuarantor() {
        GuarantorResponseDTO created = service.add(patientId, hospitalId, request("Mariam Kaboré", false));

        assertThat(created.getFullName()).isEqualTo("Mariam Kaboré");
        assertThat(created.isActive()).isTrue();
        assertThat(created.isPrimary()).isFalse();
    }

    @Test
    void addRequiresAHospitalScope() {
        GuarantorRequestDTO dto = request("X", false);

        assertThatThrownBy(() -> service.add(patientId, null, dto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital");
    }

    @Test
    void addRefusesAnUnregisteredPatient() {
        UUID foreignHospitalId = UUID.randomUUID();
        Hospital foreign = new Hospital();
        foreign.setId(foreignHospitalId);
        when(hospitalRepository.findById(foreignHospitalId)).thenReturn(Optional.of(foreign));
        GuarantorRequestDTO dto = request("X", false);

        assertThatThrownBy(() -> service.add(patientId, foreignHospitalId, dto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not registered");
    }

    @Test
    void addingAPrimaryDemotesTheExistingOne() {
        PatientGuarantor existing = stored(true, true);
        when(guarantorRepository.findByPatient_IdAndHospital_IdAndActiveTrue(patientId, hospitalId))
            .thenReturn(List.of(existing));

        GuarantorResponseDTO created = service.add(patientId, hospitalId, request("New Primary", true));

        assertThat(created.isPrimary()).isTrue();
        assertThat(existing.isPrimary()).isFalse();
    }

    @Test
    void updateRewritesFieldsAndCanPromote() {
        PatientGuarantor existing = stored(false, true);
        when(guarantorRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        GuarantorResponseDTO updated =
            service.update(patientId, existing.getId(), hospitalId, request("Renamed", true));

        assertThat(updated.getFullName()).isEqualTo("Renamed");
        assertThat(updated.isPrimary()).isTrue();
    }

    @Test
    void updateIs404ForAnotherPatientsGuarantor() {
        PatientGuarantor foreign = stored(false, true);
        Patient other = Patient.builder().firstName("X").lastName("Y").build();
        other.setId(UUID.randomUUID());
        foreign.setPatient(other);
        when(guarantorRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        UUID foreignId = foreign.getId();
        GuarantorRequestDTO dto = request("X", false);

        assertThatThrownBy(() -> service.update(patientId, foreignId, hospitalId, dto))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Guarantor not found");
    }

    @Test
    void deactivateClearsPrimaryAndNeverDeletes() {
        PatientGuarantor existing = stored(true, true);
        when(guarantorRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        GuarantorResponseDTO deactivated = service.deactivate(patientId, existing.getId(), hospitalId);

        assertThat(deactivated.isActive()).isFalse();
        assertThat(deactivated.isPrimary()).isFalse();
    }

    @Test
    void doubleDeactivateIsRefused() {
        PatientGuarantor existing = stored(false, false);
        when(guarantorRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        UUID id = existing.getId();

        assertThatThrownBy(() -> service.deactivate(patientId, id, hospitalId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already inactive");
    }

    @Test
    void reactivateRestoresAnInactiveGuarantor() {
        PatientGuarantor existing = stored(false, false);
        when(guarantorRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        GuarantorResponseDTO reactivated = service.reactivate(patientId, existing.getId(), hospitalId);

        assertThat(reactivated.isActive()).isTrue();
    }

    @Test
    void listIs404ForAScopedCallerWithoutRegistration() {
        UUID foreignScope = UUID.randomUUID();

        assertThatThrownBy(() -> service.list(patientId, foreignScope))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Patient not found");
    }

    @Test
    void listMapsRows() {
        PatientGuarantor existing = stored(true, true);
        when(guarantorRepository.findForPatient(patientId, hospitalId)).thenReturn(List.of(existing));

        List<GuarantorResponseDTO> list = service.list(patientId, hospitalId);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getPatientId()).isEqualTo(patientId);
        assertThat(list.get(0).isPrimary()).isTrue();
    }
}
