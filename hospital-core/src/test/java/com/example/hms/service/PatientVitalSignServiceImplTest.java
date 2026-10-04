package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PatientVitalSignMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.impl.PatientVitalSignServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    @Mock
    private PatientVitalSignMapper vitalSignMapper;

    @InjectMocks
    private PatientVitalSignServiceImpl patientVitalSignService;

    @Test
    void recordVital_persistsMeasurementWithResolvedContext() {
        UUID patientId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();
        UUID recorderUserId = UUID.randomUUID();
        UUID vitalId = UUID.randomUUID();

        Patient patient = new Patient();
        patient.setId(patientId);
        patient.setHospitalId(hospitalId);

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);

        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setId(registrationId);
        registration.setPatient(patient);
        registration.setHospital(hospital);
        registration.setRegistrationDate(LocalDate.now());

        Staff staff = new Staff();
        staff.setId(staffId);
        staff.setHospital(hospital);

        PatientVitalSignRequestDTO request = PatientVitalSignRequestDTO.builder()
            .registrationId(registrationId)
            .hospitalId(hospitalId)
            .recordedByStaffId(staffId)
            .heartRateBpm(92)
            .spo2Percent(98)
            .systolicBpMmHg(118)
            .diastolicBpMmHg(74)
            .recordedAt(LocalDateTime.now().minusMinutes(3))
            .build();

        PatientVitalSignResponseDTO responseDTO = PatientVitalSignResponseDTO.builder()
            .id(vitalId)
            .patientId(patientId)
            .hospitalId(hospitalId)
            .heartRateBpm(92)
            .spo2Percent(98)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findById(registrationId)).thenReturn(Optional.of(registration));
        when(staffRepository.findByIdAndActiveTrue(staffId)).thenReturn(Optional.of(staff));
        when(vitalSignRepository.save(any(PatientVitalSign.class))).thenAnswer(invocation -> {
            PatientVitalSign entity = invocation.getArgument(0);
            entity.setId(vitalId);
            entity.setRecordedAt(entity.getRecordedAt() != null ? entity.getRecordedAt() : LocalDateTime.now());
            return entity;
        });
        when(vitalSignMapper.toResponse(any(PatientVitalSign.class))).thenReturn(responseDTO);
        doAnswer(invocation -> {
            PatientVitalSignRequestDTO dto = invocation.getArgument(0);
            PatientVitalSign entity = invocation.getArgument(1);
            entity.setHeartRateBpm(dto.getHeartRateBpm());
            entity.setSpo2Percent(dto.getSpo2Percent());
            entity.setSystolicBpMmHg(dto.getSystolicBpMmHg());
            entity.setDiastolicBpMmHg(dto.getDiastolicBpMmHg());
            entity.setRecordedAt(dto.getRecordedAt());
            return null;
        }).when(vitalSignMapper).applyRequestToEntity(eq(request), any(PatientVitalSign.class));

        PatientVitalSignResponseDTO result = patientVitalSignService.recordVital(patientId, request, recorderUserId);

        assertNotNull(result);
        assertEquals(vitalId, result.getId());
        ArgumentCaptor<PatientVitalSign> captor = ArgumentCaptor.forClass(PatientVitalSign.class);
        verify(vitalSignRepository).save(captor.capture());
        PatientVitalSign saved = captor.getValue();
        assertEquals(patientId, saved.getPatient().getId());
        assertEquals(hospitalId, saved.getHospital().getId());
        assertEquals(staffId, saved.getRecordedByStaff().getId());
        assertEquals(92, saved.getHeartRateBpm());
        assertEquals(98, saved.getSpo2Percent());
    }

    // getRecentVitals is pinned in service.impl.PatientVitalSignServiceImplTest:
    // the two cases that stood here asserted the unscoped, ungated read the
    // staff path no longer performs (a null scope now refuses; a scoped read
    // goes through the chart gate and the readable hospitals).

    @Test
    void getLatestSnapshot_returnsEmptyWhenNoVitalsPresent() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();

        when(vitalSignRepository.findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.empty());

        Optional<PatientResponseDTO.VitalSnapshot> result = patientVitalSignService
            .getLatestSnapshot(patientId, hospitalId);

        assertEquals(Optional.empty(), result);
        verify(vitalSignRepository)
            .findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(patientId, hospitalId);
    }

    @Test
    void getLatestSnapshot_returnsMappedSnapshotWhenPresent() {
        UUID patientId = UUID.randomUUID();
        PatientVitalSign entity = PatientVitalSign.builder().build();
        PatientResponseDTO.VitalSnapshot snapshot = PatientResponseDTO.VitalSnapshot.builder()
            .heartRate(88)
            .bloodPressure("118/72")
            .spo2(97)
            .recordedAt(LocalDateTime.now())
            .build();

        when(vitalSignRepository.findFirstByPatient_IdOrderByRecordedAtDesc(patientId))
            .thenReturn(Optional.of(entity));
        when(vitalSignMapper.toSnapshot(entity)).thenReturn(snapshot);

        Optional<PatientResponseDTO.VitalSnapshot> result = patientVitalSignService
            .getLatestSnapshot(patientId, null);

        assertEquals(Optional.of(snapshot), result);
        verify(vitalSignRepository).findFirstByPatient_IdOrderByRecordedAtDesc(patientId);
    }

    @Test
    void recordVital_throwsWhenPatientMissing() {
        UUID patientId = UUID.randomUUID();
        PatientVitalSignRequestDTO request = new PatientVitalSignRequestDTO();

        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
            () -> patientVitalSignService.recordVital(patientId, request, null));
        verifyNoInteractions(vitalSignRepository);
    }

    @Test
    void recordVital_throwsWhenRecorderHospitalMismatch() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID staffId = UUID.randomUUID();

        Patient patient = new Patient();
        patient.setId(patientId);

        Hospital resolvedHospital = new Hospital();
        resolvedHospital.setId(hospitalId);

        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setId(UUID.randomUUID());
        registration.setPatient(patient);
        registration.setHospital(resolvedHospital);

        Staff staff = new Staff();
        staff.setId(staffId);
        Hospital otherHospital = new Hospital();
        otherHospital.setId(UUID.randomUUID());
        staff.setHospital(otherHospital);

        PatientVitalSignRequestDTO request = PatientVitalSignRequestDTO.builder()
            .registrationId(registration.getId())
            .hospitalId(hospitalId)
            .recordedByStaffId(staffId)
            .build();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findById(registration.getId())).thenReturn(Optional.of(registration));
        when(staffRepository.findByIdAndActiveTrue(staffId)).thenReturn(Optional.of(staff));

        assertThrows(BusinessException.class,
            () -> patientVitalSignService.recordVital(patientId, request, null));
        verifyNoInteractions(vitalSignRepository);
    }
}
