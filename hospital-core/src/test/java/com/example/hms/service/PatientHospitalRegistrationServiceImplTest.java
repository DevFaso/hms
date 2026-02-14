package com.example.hms.service;

import com.example.hms.exception.PatientAlreadyRegisteredException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientHospitalRegistrationMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.PatientHospitalRegistrationRequestDTO;
import com.example.hms.payload.dto.PatientHospitalRegistrationResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientHospitalRegistrationServiceImplTest {

    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientHospitalRegistrationMapper mapper;

    @InjectMocks private PatientHospitalRegistrationServiceImpl service;

    private UUID patientId, hospitalId, registrationId;
    private Patient patient;
    private Hospital hospital;
    private PatientHospitalRegistration registration;
    private PatientHospitalRegistrationResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        registrationId = UUID.randomUUID();

        patient = Patient.builder().dateOfBirth(LocalDate.of(1990, 1, 1)).build();
        patient.setId(patientId);

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("General Hospital");

        registration = PatientHospitalRegistration.builder()
            .mrn("mrn-TEST123")
            .registrationDate(LocalDate.now())
            .active(true)
            .build();
        registration.setId(registrationId);
        registration.setPatient(patient);
        registration.setHospital(hospital);

        responseDTO = new PatientHospitalRegistrationResponseDTO();
    }

    // ---- registerPatient ----

    @Test
    void registerPatient_byPatientId_success() {
        PatientHospitalRegistrationRequestDTO dto = PatientHospitalRegistrationRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId).build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(false);
        when(registrationRepository.existsByMrnAndHospitalId(anyString(), eq(hospitalId))).thenReturn(false);
        when(mapper.toEntity(dto, patient, hospital)).thenReturn(registration);
        when(registrationRepository.save(registration)).thenReturn(registration);
        when(mapper.toResponseDTO(registration)).thenReturn(responseDTO);

        PatientHospitalRegistrationResponseDTO result = service.registerPatient(dto);
        assertThat(result).isEqualTo(responseDTO);
        verify(registrationRepository).save(registration);
    }

    @Test
    void registerPatient_byUsername_success() {
        PatientHospitalRegistrationRequestDTO dto = PatientHospitalRegistrationRequestDTO.builder()
            .patientUsername("john.doe").hospitalName("General Hospital").build();

        when(patientRepository.findByUsernameOrEmail("john.doe")).thenReturn(Optional.of(patient));
        when(hospitalRepository.findByName("General Hospital")).thenReturn(Optional.of(hospital));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(false);
        when(registrationRepository.existsByMrnAndHospitalId(anyString(), eq(hospitalId))).thenReturn(false);
        when(mapper.toEntity(dto, patient, hospital)).thenReturn(registration);
        when(registrationRepository.save(registration)).thenReturn(registration);
        when(mapper.toResponseDTO(registration)).thenReturn(responseDTO);

        PatientHospitalRegistrationResponseDTO result = service.registerPatient(dto);
        assertThat(result).isEqualTo(responseDTO);
    }

    @Test
    void registerPatient_alreadyRegistered_throws() {
        PatientHospitalRegistrationRequestDTO dto = PatientHospitalRegistrationRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId).build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(true);

        assertThatThrownBy(() -> service.registerPatient(dto))
            .isInstanceOf(PatientAlreadyRegisteredException.class);
    }

    @Test
    void registerPatient_patientNotFound_throws() {
        PatientHospitalRegistrationRequestDTO dto = PatientHospitalRegistrationRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId).build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerPatient(dto))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void registerPatient_hospitalNotFound_throws() {
        PatientHospitalRegistrationRequestDTO dto = PatientHospitalRegistrationRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId).build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerPatient(dto))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void registerPatient_noPatientRef_throwsIllegalArg() {
        PatientHospitalRegistrationRequestDTO dto = PatientHospitalRegistrationRequestDTO.builder()
            .hospitalId(hospitalId).build();

        assertThatThrownBy(() -> service.registerPatient(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Patient");
    }

    @Test
    void registerPatient_noHospitalRef_throwsIllegalArg() {
        PatientHospitalRegistrationRequestDTO dto = PatientHospitalRegistrationRequestDTO.builder()
            .patientId(patientId).build();

        assertThatThrownBy(() -> service.registerPatient(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Hospital");
    }

    @Test
    void registerPatient_minorWithoutGuardian_throwsIllegalArg() {
        patient.setDateOfBirth(LocalDate.now().minusYears(10));
        patient.setEmergencyContactName(null);
        patient.setEmergencyContactPhone(null);
        patient.setEmergencyContactRelationship(null);
        PatientHospitalRegistrationRequestDTO dto = PatientHospitalRegistrationRequestDTO.builder()
            .patientId(patientId).hospitalId(hospitalId).build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId)).thenReturn(false);

        assertThatThrownBy(() -> service.registerPatient(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("guardian");
    }

    // ---- getById ----

    @Test
    void getById_success() {
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(mapper.toResponseDTO(registration)).thenReturn(responseDTO);

        assertThat(service.getById(registrationId)).isEqualTo(responseDTO);
    }

    @Test
    void getById_notFound() {
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(registrationId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- getRegistrationsByPatient (username) ----

    @Test
    void getRegistrationsByPatient_byUsername() {
        when(registrationRepository.findByPatientUsername("john")).thenReturn(List.of(registration));
        when(mapper.toResponseDTO(registration)).thenReturn(responseDTO);

        List<PatientHospitalRegistrationResponseDTO> result = service.getRegistrationsByPatient("john");
        assertThat(result).hasSize(1);
    }

    // ---- getRegistrationsByPatient (UUID, paginated) ----

    @Test
    void getRegistrationsByPatient_byUUID_paginated() {
        when(registrationRepository.findByPatientId(patientId)).thenReturn(List.of(registration));
        when(mapper.toResponseDTO(registration)).thenReturn(responseDTO);

        List<PatientHospitalRegistrationResponseDTO> result = service.getRegistrationsByPatient(patientId, 0, 10, null);
        assertThat(result).hasSize(1);
    }

    @Test
    void getRegistrationsByPatient_byUUID_filteredByActive() {
        when(registrationRepository.findByPatientId(patientId)).thenReturn(List.of(registration));
        when(mapper.toResponseDTO(registration)).thenReturn(responseDTO);

        List<PatientHospitalRegistrationResponseDTO> result = service.getRegistrationsByPatient(patientId, 0, 10, true);
        assertThat(result).hasSize(1);
    }

    @Test
    void getRegistrationsByPatient_byUUID_simple() {
        when(registrationRepository.findByPatientId(patientId)).thenReturn(List.of(registration));
        when(mapper.toResponseDTO(registration)).thenReturn(responseDTO);

        assertThat(service.getRegistrationsByPatient(patientId)).hasSize(1);
    }

    // ---- getRegistrationsByHospital ----

    @Test
    void getRegistrationsByHospital_success() {
        when(registrationRepository.findByHospitalId(hospitalId)).thenReturn(List.of(registration));
        when(mapper.toResponseDTO(registration)).thenReturn(responseDTO);

        List<PatientHospitalRegistrationResponseDTO> result = service.getRegistrationsByHospital(hospitalId, 0, 10, null);
        assertThat(result).hasSize(1);
    }

    // ---- updateRegistration (UUID-based) ----

    @Test
    void updateRegistration_byUUID_success() {
        PatientHospitalRegistrationRequestDTO dto = PatientHospitalRegistrationRequestDTO.builder()
            .currentRoom("Room A").currentBed("Bed 1").build();
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(registrationRepository.save(registration)).thenReturn(registration);
        when(mapper.toResponseDTO(registration)).thenReturn(responseDTO);

        assertThat(service.updateRegistration(registrationId, dto)).isEqualTo(responseDTO);
    }

    @Test
    void updateRegistration_byUUID_notFound() {
        PatientHospitalRegistrationRequestDTO dto = PatientHospitalRegistrationRequestDTO.builder().build();
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateRegistration(registrationId, dto))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- updateRegistration (MRN-based) ----

    @Test
    void updateRegistration_byMrn_success() {
        PatientHospitalRegistrationRequestDTO dto = PatientHospitalRegistrationRequestDTO.builder()
            .hospitalName("General Hospital").currentRoom("Room B").build();
        when(registrationRepository.findByMrnAndHospitalName("mrn-TEST123", "General Hospital"))
            .thenReturn(Optional.of(registration));
        when(registrationRepository.save(registration)).thenReturn(registration);
        when(mapper.toResponseDTO(registration)).thenReturn(responseDTO);

        assertThat(service.updateRegistration("mrn-TEST123", dto)).isEqualTo(responseDTO);
    }

    // ---- patchRegistration ----

    @Test
    void patchRegistration_byUUID_success() {
        PatientHospitalRegistrationRequestDTO dto = PatientHospitalRegistrationRequestDTO.builder()
            .currentRoom("Room C").build();
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(registrationRepository.save(registration)).thenReturn(registration);
        when(mapper.toResponseDTO(registration)).thenReturn(responseDTO);

        assertThat(service.patchRegistration(registrationId, dto)).isEqualTo(responseDTO);
    }

    @Test
    void patchRegistration_byMrn_success() {
        PatientHospitalRegistrationRequestDTO dto = PatientHospitalRegistrationRequestDTO.builder()
            .hospitalName("General Hospital").currentBed("Bed 2").build();
        when(registrationRepository.findByMrnAndHospitalName("mrn-TEST123", "General Hospital"))
            .thenReturn(Optional.of(registration));
        when(registrationRepository.save(registration)).thenReturn(registration);
        when(mapper.toResponseDTO(registration)).thenReturn(responseDTO);

        assertThat(service.patchRegistration("mrn-TEST123", dto)).isEqualTo(responseDTO);
    }

    // ---- deregisterPatient ----

    @Test
    void deregisterPatient_byMrn_success() {
        when(registrationRepository.findByMrn("mrn-TEST123")).thenReturn(Optional.of(registration));
        service.deregisterPatient("mrn-TEST123");
        verify(registrationRepository).delete(registration);
    }

    @Test
    void deregisterPatient_byMrn_notFound() {
        when(registrationRepository.findByMrn("mrn-MISSING")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deregisterPatient("mrn-MISSING"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deregisterPatient_byUUID_success() {
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        service.deregisterPatient(registrationId);
        verify(registrationRepository).delete(registration);
    }

    @Test
    void deregisterPatient_byUUID_notFound() {
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deregisterPatient(registrationId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- getPatientsRegisteredInMultipleHospitals ----

    @Test
    void getPatientsRegisteredInMultipleHospitals_delegates() {
        when(registrationRepository.findPatientsRegisteredInMultipleHospitals()).thenReturn(List.of());
        assertThat(service.getPatientsRegisteredInMultipleHospitals()).isEmpty();
    }
}
