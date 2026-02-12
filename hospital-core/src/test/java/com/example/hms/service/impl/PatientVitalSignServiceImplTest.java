package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientVitalSignMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class PatientVitalSignServiceImplTest {

    @Mock
    private PatientRepository patientRepository;

    @Mock
    private PatientHospitalRegistrationRepository registrationRepository;

    @Mock
    private HospitalRepository hospitalRepository;

    @Mock
    private StaffRepository staffRepository;

    @Mock
    private UserRoleHospitalAssignmentRepository assignmentRepository;

    @Mock
    private PatientVitalSignRepository vitalSignRepository;

    private PatientVitalSignServiceImpl service;

    private final PatientVitalSignMapper mapper = new PatientVitalSignMapper();

    @BeforeEach
    void setUp() {
        service = new PatientVitalSignServiceImpl(
            patientRepository,
            registrationRepository,
            hospitalRepository,
            staffRepository,
            assignmentRepository,
            vitalSignRepository,
            mapper
        );
    }

    @Test
    void recordVitalResolvesContextFromRegistrationAndRecorderUser() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID recorderUserId = UUID.randomUUID();

        Patient patient = minimalPatient(patientId);
        Hospital hospital = minimalHospital(hospitalId);
        PatientHospitalRegistration registration = minimalRegistration(patient, hospital);

        UserRoleHospitalAssignment staffAssignment = minimalAssignment(hospital, true);
        Staff staff = minimalStaff(recorderUserId, hospital, staffAssignment);

        PatientVitalSignRequestDTO request = PatientVitalSignRequestDTO.builder()
            .hospitalId(hospitalId)
            .recordedByStaffId(null)
            .recordedByAssignmentId(null)
            .recordedAt(LocalDateTime.now())
            .temperatureCelsius(37.5)
            .heartRateBpm(80)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(registration));
        when(staffRepository.findByUserIdAndHospitalId(recorderUserId, hospitalId))
            .thenReturn(Optional.of(staff));
        when(vitalSignRepository.save(any(PatientVitalSign.class)))
            .thenAnswer(invocation -> {
                PatientVitalSign entity = invocation.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

        PatientVitalSignResponseDTO response = service.recordVital(patientId, request, recorderUserId);

        assertThat(response.getPatientId()).isEqualTo(patientId);
        assertThat(response.getHospitalId()).isEqualTo(hospitalId);
        assertThat(response.getRecordedByStaffId()).isEqualTo(staff.getId());

        ArgumentCaptor<PatientVitalSign> captor = ArgumentCaptor.forClass(PatientVitalSign.class);
        verify(vitalSignRepository).save(captor.capture());
        assertThat(captor.getValue().getRegistration()).isEqualTo(registration);
        assertThat(captor.getValue().getRecordedByAssignment()).isEqualTo(staffAssignment);
    }

    @Test
    void recordVitalThrowsWhenAssignmentHospitalMismatch() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID recorderUserId = UUID.randomUUID();

        Patient patient = minimalPatient(patientId);
        Hospital hospital = minimalHospital(hospitalId);
        PatientHospitalRegistration registration = minimalRegistration(patient, hospital);

        UserRoleHospitalAssignment mismatchedAssignment = minimalAssignment(minimalHospital(UUID.randomUUID()), true);
        PatientVitalSignRequestDTO request = PatientVitalSignRequestDTO.builder()
            .hospitalId(hospitalId)
            .recordedByAssignmentId(assignmentId)
            .recordedAt(LocalDateTime.now())
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(registration));
        when(staffRepository.findByUserIdAndHospitalId(recorderUserId, hospitalId))
            .thenReturn(Optional.of(minimalStaff(recorderUserId, hospital, minimalAssignment(hospital, true))));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(mismatchedAssignment));

        assertThatThrownBy(() -> service.recordVital(patientId, request, recorderUserId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("assignment is not associated");

        verify(vitalSignRepository, never()).save(any());
    }

    @Test
    void recordVitalThrowsWhenRecorderStaffHospitalMismatch() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();

        Patient patient = minimalPatient(patientId);
        Hospital hospital = minimalHospital(hospitalId);
        PatientHospitalRegistration registration = minimalRegistration(patient, hospital);

        Staff staff = minimalStaff(UUID.randomUUID(), minimalHospital(UUID.randomUUID()), null);
        staff.setId(staffId);

        PatientVitalSignRequestDTO request = PatientVitalSignRequestDTO.builder()
            .hospitalId(hospitalId)
            .recordedByStaffId(staffId)
            .recordedAt(LocalDateTime.now())
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(registration));
        when(staffRepository.findByIdAndActiveTrue(staffId)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> service.recordVital(patientId, request, UUID.randomUUID()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Recorder staff is not assigned");
    }

    @Test
    void recordVitalThrowsWhenRequestedHospitalNotFound() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        Patient patient = minimalPatient(patientId);
        patient.setHospitalId(null);

        PatientVitalSignRequestDTO request = PatientVitalSignRequestDTO.builder()
            .hospitalId(hospitalId)
            .recordedAt(LocalDateTime.now())
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.empty());
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordVital(patientId, request, UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Hospital not found");
    }

    @Test
    void recordVitalUsesFirstActiveRegistrationWhenHospitalNotSpecified() {
        UUID patientId = UUID.randomUUID();
        UUID recorderUserId = UUID.randomUUID();

        Patient patient = minimalPatient(patientId);
        patient.setHospitalId(UUID.randomUUID());
        when(hospitalRepository.findById(patient.getHospitalId())).thenReturn(Optional.empty());

        UserRoleHospitalAssignment assignment = minimalAssignment(minimalHospital(UUID.randomUUID()), true);
        Staff staff = minimalStaff(recorderUserId, assignment.getHospital(), assignment);

        PatientVitalSignRequestDTO request = PatientVitalSignRequestDTO.builder()
            .recordedAt(LocalDateTime.now())
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientId(patientId)).thenReturn(List.of());
        when(staffRepository.findFirstByUserIdOrderByCreatedAtAsc(recorderUserId)).thenReturn(Optional.of(staff));
        when(vitalSignRepository.save(any(PatientVitalSign.class)))
            .thenAnswer(invocation -> {
                PatientVitalSign entity = invocation.getArgument(0);
                entity.setId(UUID.randomUUID());
                return entity;
            });

        PatientVitalSignResponseDTO response = service.recordVital(patientId, request, recorderUserId);

        assertThat(response.getRegistrationId()).isNull();
        assertThat(response.getHospitalId()).isNull();
    }

    @Test
    void recordVitalThrowsWhenRegistrationBelongsToDifferentPatient() {
        UUID patientId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();

        Patient patient = minimalPatient(patientId);
        Patient otherPatient = minimalPatient(UUID.randomUUID());
        PatientHospitalRegistration registration = minimalRegistration(otherPatient, minimalHospital(UUID.randomUUID()));
        registration.setId(registrationId);

        PatientVitalSignRequestDTO request = PatientVitalSignRequestDTO.builder()
            .registrationId(registrationId)
            .recordedAt(LocalDateTime.now())
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));

        assertThatThrownBy(() -> service.recordVital(patientId, request, UUID.randomUUID()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Registration does not belong");
    }

    @Test
    void recordVitalThrowsWhenAssignmentInactive() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();

        Patient patient = minimalPatient(patientId);
        Hospital hospital = minimalHospital(hospitalId);
        PatientHospitalRegistration registration = minimalRegistration(patient, hospital);

        UserRoleHospitalAssignment inactiveAssignment = minimalAssignment(hospital, false);

        PatientVitalSignRequestDTO request = PatientVitalSignRequestDTO.builder()
            .hospitalId(hospitalId)
            .recordedByAssignmentId(assignmentId)
            .recordedAt(LocalDateTime.now())
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(registration));
        when(staffRepository.findByUserIdAndHospitalId(any(), any()))
            .thenReturn(Optional.of(minimalStaff(UUID.randomUUID(), hospital, null)));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(inactiveAssignment));

        assertThatThrownBy(() -> service.recordVital(patientId, request, UUID.randomUUID()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Assignment is not active");
    }

    @Test
    void recordVitalThrowsWhenAssignmentNotFound() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID recorderUserId = UUID.randomUUID();

        Patient patient = minimalPatient(patientId);
        Hospital hospital = minimalHospital(hospitalId);
        PatientHospitalRegistration registration = minimalRegistration(patient, hospital);
        Staff staff = minimalStaff(recorderUserId, hospital, minimalAssignment(hospital, true));

        PatientVitalSignRequestDTO request = PatientVitalSignRequestDTO.builder()
            .hospitalId(hospitalId)
            .recordedByAssignmentId(assignmentId)
            .recordedAt(LocalDateTime.now())
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(registration));
        when(staffRepository.findByUserIdAndHospitalId(recorderUserId, hospitalId)).thenReturn(Optional.of(staff));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordVital(patientId, request, recorderUserId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Assignment not found");
    }

    @Test
    void recordVitalThrowsWhenStaffIdInvalid() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();

        Patient patient = minimalPatient(patientId);
        Hospital hospital = minimalHospital(hospitalId);
        PatientHospitalRegistration registration = minimalRegistration(patient, hospital);

        PatientVitalSignRequestDTO request = PatientVitalSignRequestDTO.builder()
            .hospitalId(hospitalId)
            .recordedByStaffId(staffId)
            .recordedAt(LocalDateTime.now())
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId))
            .thenReturn(Optional.of(registration));
        when(staffRepository.findByIdAndActiveTrue(staffId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordVital(patientId, request, UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Staff not found or inactive");
    }

    @Test
    void recordVitalThrowsWhenRegistrationMissing() {
        UUID patientId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();

        Patient patient = minimalPatient(patientId);

        PatientVitalSignRequestDTO request = PatientVitalSignRequestDTO.builder()
            .registrationId(registrationId)
            .recordedAt(LocalDateTime.now())
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordVital(patientId, request, UUID.randomUUID()))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Registration not found");
    }

    @Test
    void getRecentVitalsFallsBackToPatientOnlyWhenHospitalMissing() {
        UUID patientId = UUID.randomUUID();
        int limit = 0;
        PatientVitalSign vitalSign = PatientVitalSign.builder()
            .patient(minimalPatient(patientId))
            .recordedAt(LocalDateTime.now())
            .build();
        vitalSign.setId(UUID.randomUUID());

        when(vitalSignRepository.findByPatient_IdOrderByRecordedAtDesc(patientId, PageRequest.of(0, 1)))
            .thenReturn(List.of(vitalSign));

        List<PatientVitalSignResponseDTO> responses = service.getRecentVitals(patientId, null, limit);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getPatientId()).isEqualTo(patientId);
    }

    @Test
    void getRecentVitalsFiltersByHospital() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        Patient patient = minimalPatient(patientId);
        Hospital hospital = minimalHospital(hospitalId);
        PatientVitalSign vitalSign = PatientVitalSign.builder()
            .patient(patient)
            .hospital(hospital)
            .recordedAt(LocalDateTime.now())
            .build();
        vitalSign.setId(UUID.randomUUID());

        when(vitalSignRepository.findByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId, PageRequest.of(0, 3)))
            .thenReturn(List.of(vitalSign));

        List<PatientVitalSignResponseDTO> responses = service.getRecentVitals(patientId, hospitalId, 3);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getHospitalId()).isEqualTo(hospitalId);
    }

    @Test
    void getLatestSnapshotReturnsEmptyWhenNoRecords() {
        UUID patientId = UUID.randomUUID();
        when(vitalSignRepository.findFirstByPatient_IdOrderByRecordedAtDesc(patientId))
            .thenReturn(Optional.empty());

        Optional<PatientResponseDTO.VitalSnapshot> snapshot = service.getLatestSnapshot(patientId, null);

        assertThat(snapshot).isEmpty();
    }

    @Test
    void getLatestSnapshotResolvesHospitalSpecificRecord() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        Patient patient = minimalPatient(patientId);
        Hospital hospital = minimalHospital(hospitalId);
        PatientVitalSign vitalSign = PatientVitalSign.builder()
            .patient(patient)
            .hospital(hospital)
            .heartRateBpm(75)
            .systolicBpMmHg(120)
            .diastolicBpMmHg(80)
            .recordedAt(LocalDateTime.now())
            .build();

        when(vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(vitalSign));

        Optional<PatientResponseDTO.VitalSnapshot> snapshot = service.getLatestSnapshot(patientId, hospitalId);

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().getHeartRate()).isEqualTo(75);
    }

    private Patient minimalPatient(UUID id) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.setHospitalId(null);
        return patient;
    }

    private Hospital minimalHospital(UUID id) {
        Hospital hospital = Hospital.builder()
            .name("Test Hospital")
            .code("H-" + id.toString().substring(0, 4))
            .build();
        hospital.setId(id);
        return hospital;
    }

    private PatientHospitalRegistration minimalRegistration(Patient patient, Hospital hospital) {
        PatientHospitalRegistration registration = PatientHospitalRegistration.builder()
            .patient(patient)
            .hospital(hospital)
            .mrn("MRN" + patient.getId().toString().substring(0, 4))
            .build();
        registration.setId(UUID.randomUUID());
        return registration;
    }

    private UserRoleHospitalAssignment minimalAssignment(Hospital hospital, boolean active) {
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .active(active)
            .build();
        assignment.setId(UUID.randomUUID());
        assignment.setHospital(hospital);
        return assignment;
    }

    private Staff minimalStaff(UUID userId, Hospital hospital, UserRoleHospitalAssignment assignment) {
        User user = new User();
        user.setId(userId);
        user.setUsername("user-" + userId);
        user.setPasswordHash("hash");
        user.setEmail("user" + userId + "@example.com");
        user.setPhoneNumber("000-000" + userId.toString().substring(0, 2));

        Staff staff = new Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(user);
        staff.setHospital(hospital);
        staff.setAssignment(assignment);
        staff.setLicenseNumber("LIC" + userId.toString().substring(0, 4));
        staff.setActive(true);
        return staff;
    }
}
