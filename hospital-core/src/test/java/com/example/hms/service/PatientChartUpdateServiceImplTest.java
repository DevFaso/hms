package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientChartUpdateMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.chart.PatientChartUpdate;
import com.example.hms.payload.dto.DoctorPatientChartUpdateRequestDTO;
import com.example.hms.payload.dto.PatientChartUpdateResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientChartUpdateRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientChartUpdateServiceImplTest {

    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private PatientChartUpdateRepository patientChartUpdateRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private PatientChartUpdateMapper patientChartUpdateMapper;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private PatientChartUpdateServiceImpl service;

    private UUID patientId, hospitalId, staffUserId, updateId;
    private Patient patient;
    private Hospital hospital;
    private Staff staff;
    private UserRoleHospitalAssignment assignment;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        staffUserId = UUID.randomUUID();
        updateId = UUID.randomUUID();

        patient = Patient.builder().firstName("John").lastName("Doe").email("j@t.com").build();
        patient.setId(patientId);

        hospital = Hospital.builder().name("H1").code("H1").build();
        hospital.setId(hospitalId);

        User user = new User();
        user.setId(staffUserId);
        staff = Staff.builder().user(user).hospital(hospital).licenseNumber("L1").build();
        staff.setId(UUID.randomUUID());

        assignment = new UserRoleHospitalAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(hospital);
    }

    @Test
    void listPatientChartUpdates_success() {
        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        PatientChartUpdate update = PatientChartUpdate.builder().patient(patient).hospital(hospital).build();
        Page<PatientChartUpdate> page = new PageImpl<>(List.of(update));
        when(patientChartUpdateRepository.findByPatient_IdAndHospital_Id(eq(patientId), eq(hospitalId), any(Pageable.class))).thenReturn(page);
        when(patientChartUpdateMapper.toResponseDto(any())).thenReturn(PatientChartUpdateResponseDTO.builder().build());

        Page<PatientChartUpdateResponseDTO> result = service.listPatientChartUpdates(patientId, hospitalId, PageRequest.of(0, 10));
        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void listPatientChartUpdates_patientNotFound() {
        when(patientRepository.existsById(patientId)).thenReturn(false);
        Pageable pageable = PageRequest.of(0, 10);
        assertThatThrownBy(() -> service.listPatientChartUpdates(patientId, hospitalId, pageable))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listPatientChartUpdates_notRegistered() {
        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(false);
        Pageable pageable = PageRequest.of(0, 10);
        assertThatThrownBy(() -> service.listPatientChartUpdates(patientId, hospitalId, pageable))
                .isInstanceOf(BusinessException.class);
    }    @Test
    void getPatientChartUpdate_success() {
        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        PatientChartUpdate update = PatientChartUpdate.builder().patient(patient).hospital(hospital).build();
        update.setId(updateId);
        when(patientChartUpdateRepository.findById(updateId)).thenReturn(Optional.of(update));
        when(patientChartUpdateMapper.toResponseDto(update)).thenReturn(PatientChartUpdateResponseDTO.builder().build());

        PatientChartUpdateResponseDTO result = service.getPatientChartUpdate(patientId, hospitalId, updateId);
        assertThat(result).isNotNull();
    }

    @Test
    void getPatientChartUpdate_notFound() {
        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(patientChartUpdateRepository.findById(updateId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getPatientChartUpdate(patientId, hospitalId, updateId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createPatientChartUpdate_success() {
        DoctorPatientChartUpdateRequestDTO request = new DoctorPatientChartUpdateRequestDTO();
        request.setUpdateReason("Follow-up");
        request.setHospitalId(hospitalId);

        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findByUserIdAndHospitalId(staffUserId, hospitalId)).thenReturn(Optional.of(staff));
        when(patientChartUpdateRepository.findTopByPatient_IdAndHospital_IdOrderByVersionNumberDesc(patientId, hospitalId))
            .thenReturn(Optional.empty());
        when(patientChartUpdateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(patientChartUpdateMapper.toResponseDto(any())).thenReturn(PatientChartUpdateResponseDTO.builder().build());

        PatientChartUpdateResponseDTO result = service.createPatientChartUpdate(patientId, hospitalId, staffUserId, assignment, request);
        assertThat(result).isNotNull();
        verify(patientChartUpdateRepository).save(any());
    }

    @Test
    void createPatientChartUpdate_nullRequest() {
        assertThatThrownBy(() -> service.createPatientChartUpdate(patientId, hospitalId, staffUserId, assignment, null))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void createPatientChartUpdate_noHospitalContext() {
        DoctorPatientChartUpdateRequestDTO request = new DoctorPatientChartUpdateRequestDTO();
        request.setUpdateReason("Test");
        assertThatThrownBy(() -> service.createPatientChartUpdate(patientId, null, staffUserId, assignment, request))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void createPatientChartUpdate_assignmentMismatch() {
        DoctorPatientChartUpdateRequestDTO request = new DoctorPatientChartUpdateRequestDTO();
        request.setUpdateReason("Test");
        request.setHospitalId(hospitalId);

        Hospital otherHospital = Hospital.builder().name("Other").code("OT").build();
        otherHospital.setId(UUID.randomUUID());
        assignment.setHospital(otherHospital);

        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);

        assertThatThrownBy(() -> service.createPatientChartUpdate(patientId, hospitalId, staffUserId, assignment, request))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void createPatientChartUpdate_noUpdateReason() {
        DoctorPatientChartUpdateRequestDTO request = new DoctorPatientChartUpdateRequestDTO();
        request.setHospitalId(hospitalId);

        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(registrationRepository.isPatientRegisteredInHospitalFixed(patientId, hospitalId)).thenReturn(true);
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findByUserIdAndHospitalId(staffUserId, hospitalId)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> service.createPatientChartUpdate(patientId, hospitalId, staffUserId, assignment, request))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void listPatientChartUpdates_nullPatientId() {
        Pageable pageable = PageRequest.of(0, 10);
        assertThatThrownBy(() -> service.listPatientChartUpdates(null, hospitalId, pageable))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void listPatientChartUpdates_nullHospitalId() {
        Pageable pageable = PageRequest.of(0, 10);
        assertThatThrownBy(() -> service.listPatientChartUpdates(patientId, null, pageable))
            .isInstanceOf(BusinessException.class);
    }
}
