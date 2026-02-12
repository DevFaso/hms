package com.example.hms.service.impl;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.DischargeDisposition;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.AdmissionMapper;
import com.example.hms.model.*;
import com.example.hms.payload.dto.*;
import com.example.hms.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdmissionServiceImplTest {

    @Mock private AdmissionRepository admissionRepository;
    @Mock private AdmissionOrderSetRepository orderSetRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private AdmissionMapper admissionMapper;

    @InjectMocks
    private AdmissionServiceImpl service;

    private UUID patientId, hospitalId, staffId, admissionId;
    private Patient patient;
    private Hospital hospital;
    private Staff staff;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        admissionId = UUID.randomUUID();
        patient = new Patient(); patient.setId(patientId);
        hospital = Hospital.builder().build(); hospital.setId(hospitalId);
        staff = new Staff(); staff.setId(staffId);
    }

    @Test
    void admitPatient_success() {
        AdmissionRequestDTO request = new AdmissionRequestDTO();
        request.setPatientId(patientId);
        request.setHospitalId(hospitalId);
        request.setAdmittingProviderId(staffId);

        Admission admission = new Admission();
        admission.setId(admissionId);
        AdmissionResponseDTO response = new AdmissionResponseDTO();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(admissionRepository.save(any(Admission.class))).thenReturn(admission);
        when(admissionMapper.toResponseDTO(admission)).thenReturn(response);

        assertThat(service.admitPatient(request)).isEqualTo(response);
    }

    @Test
    void admitPatient_patientNotFound() {
        AdmissionRequestDTO request = new AdmissionRequestDTO();
        request.setPatientId(patientId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.admitPatient(request)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAdmission_found() {
        Admission admission = new Admission();
        AdmissionResponseDTO response = new AdmissionResponseDTO();
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(admissionMapper.toResponseDTO(admission)).thenReturn(response);
        assertThat(service.getAdmission(admissionId)).isEqualTo(response);
    }

    @Test
    void getAdmission_notFound() {
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getAdmission(admissionId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateAdmission_success() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        AdmissionUpdateRequestDTO request = new AdmissionUpdateRequestDTO();
        request.setRoomBed("Room 101");
        AdmissionResponseDTO response = new AdmissionResponseDTO();

        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(admissionRepository.save(admission)).thenReturn(admission);
        when(admissionMapper.toResponseDTO(admission)).thenReturn(response);

        assertThat(service.updateAdmission(admissionId, request)).isEqualTo(response);
        assertThat(admission.getRoomBed()).isEqualTo("Room 101");
    }

    @Test
    void cancelAdmission_success() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(AdmissionStatus.ACTIVE);
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));

        service.cancelAdmission(admissionId);

        verify(admissionRepository).save(admission);
    }

    @Test
    void cancelAdmission_notFound() {
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancelAdmission(admissionId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAdmissionsByPatient_returnsList() {
        Admission admission = new Admission();
        AdmissionResponseDTO response = new AdmissionResponseDTO();
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId)).thenReturn(List.of(admission));
        when(admissionMapper.toResponseDTO(admission)).thenReturn(response);

        List<AdmissionResponseDTO> result = service.getAdmissionsByPatient(patientId);
        assertThat(result).hasSize(1);
    }

    @Test
    void getAdmissionsByHospital_withStatus_filtersCorrectly() {
        when(admissionRepository.findByHospitalIdAndStatusOrderByAdmissionDateTimeDesc(hospitalId, AdmissionStatus.ACTIVE))
                .thenReturn(List.of());
        assertThat(service.getAdmissionsByHospital(hospitalId, "ACTIVE", null, null)).isEmpty();
    }

    @Test
    void getAdmissionsByHospital_withoutStatus_returnsAll() {
        when(admissionRepository.findByHospitalIdOrderByAdmissionDateTimeDesc(hospitalId)).thenReturn(List.of());
        assertThat(service.getAdmissionsByHospital(hospitalId, null, null, null)).isEmpty();
    }

    @Test
    void getCurrentAdmissionForPatient_noActive_returnsNull() {
        when(admissionRepository.findCurrentAdmissionByPatient(patientId)).thenReturn(Optional.empty());
        assertThat(service.getCurrentAdmissionForPatient(patientId)).isNull();
    }

    @Test
    void getOrderSet_found() {
        AdmissionOrderSet orderSet = new AdmissionOrderSet();
        AdmissionOrderSetResponseDTO response = new AdmissionOrderSetResponseDTO();
        when(orderSetRepository.findById(any())).thenReturn(Optional.of(orderSet));
        when(admissionMapper.toOrderSetResponseDTO(orderSet)).thenReturn(response);
        assertThat(service.getOrderSet(UUID.randomUUID())).isEqualTo(response);
    }

    @Test
    void getOrderSet_notFound() {
        UUID id = UUID.randomUUID();
        when(orderSetRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getOrderSet(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deactivateOrderSet_success() {
        UUID orderSetId = UUID.randomUUID();
        AdmissionOrderSet orderSet = new AdmissionOrderSet();
        orderSet.setActive(true);
        when(orderSetRepository.findById(orderSetId)).thenReturn(Optional.of(orderSet));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));

        service.deactivateOrderSet(orderSetId, "No longer needed", staffId);

        verify(orderSetRepository).save(orderSet);
    }

    @Test
    void dischargePatient_success() {
        Admission admission = new Admission();
        admission.setId(admissionId);
        admission.setStatus(AdmissionStatus.ACTIVE);
        AdmissionDischargeRequestDTO request = new AdmissionDischargeRequestDTO();
        request.setDischargingProviderId(staffId);
        request.setDischargeDisposition(DischargeDisposition.HOME);
        request.setDischargeSummary("Recovered");
        request.setDischargeInstructions("Rest");
        AdmissionResponseDTO response = new AdmissionResponseDTO();

        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(admission));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(admissionRepository.save(admission)).thenReturn(admission);
        when(admissionMapper.toResponseDTO(admission)).thenReturn(response);

        assertThat(service.dischargePatient(admissionId, request)).isEqualTo(response);
    }
}
