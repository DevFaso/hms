package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ImmunizationMapper;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientImmunization;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.medicalhistory.ImmunizationRequestDTO;
import com.example.hms.payload.dto.medicalhistory.ImmunizationResponseDTO;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImmunizationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImmunizationServiceImplTest {

    @Mock private ImmunizationRepository immunizationRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private ImmunizationMapper immunizationMapper;

    @InjectMocks
    private ImmunizationServiceImpl service;

    private UUID patientId, hospitalId, staffId, encounterId, immunizationId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        encounterId = UUID.randomUUID();
        immunizationId = UUID.randomUUID();
        patient = new Patient(); patient.setId(patientId);
        hospital = Hospital.builder().build(); hospital.setId(hospitalId);
    }

    @Test
    void createImmunization_success() {
        ImmunizationRequestDTO dto = new ImmunizationRequestDTO();
        dto.setPatientId(patientId);
        dto.setHospitalId(hospitalId);

        PatientImmunization entity = new PatientImmunization();
        entity.setId(immunizationId);
        ImmunizationResponseDTO response = new ImmunizationResponseDTO();

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(immunizationMapper.toEntity(any(), eq(patient), eq(hospital), isNull(), isNull())).thenReturn(entity);
        when(immunizationRepository.save(entity)).thenReturn(entity);
        when(immunizationMapper.toResponseDTO(entity)).thenReturn(response);

        assertThat(service.createImmunization(dto)).isEqualTo(response);
    }

    @Test
    void createImmunization_withStaffAndEncounter() {
        ImmunizationRequestDTO dto = new ImmunizationRequestDTO();
        dto.setPatientId(patientId);
        dto.setHospitalId(hospitalId);
        dto.setAdministeredByStaffId(staffId);
        dto.setEncounterId(encounterId);

        Staff staff = new Staff(); staff.setId(staffId);
        Encounter encounter = new Encounter(); encounter.setId(encounterId);
        PatientImmunization entity = new PatientImmunization();
        entity.setId(immunizationId);

        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findById(staffId)).thenReturn(Optional.of(staff));
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(immunizationMapper.toEntity(any(), eq(patient), eq(hospital), eq(staff), eq(encounter))).thenReturn(entity);
        when(immunizationRepository.save(entity)).thenReturn(entity);
        when(immunizationMapper.toResponseDTO(entity)).thenReturn(new ImmunizationResponseDTO());

        service.createImmunization(dto);
        verify(immunizationRepository).save(entity);
    }

    @Test
    void createImmunization_patientNotFound() {
        ImmunizationRequestDTO dto = new ImmunizationRequestDTO();
        dto.setPatientId(patientId);
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createImmunization(dto)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getImmunizationById_found() {
        PatientImmunization entity = new PatientImmunization();
        ImmunizationResponseDTO response = new ImmunizationResponseDTO();
        when(immunizationRepository.findById(immunizationId)).thenReturn(Optional.of(entity));
        when(immunizationMapper.toResponseDTO(entity)).thenReturn(response);
        assertThat(service.getImmunizationById(immunizationId)).isEqualTo(response);
    }

    @Test
    void getImmunizationById_notFound() {
        when(immunizationRepository.findById(immunizationId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getImmunizationById(immunizationId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getImmunizationsByPatientId_success() {
        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(immunizationRepository.findByPatient_IdOrderByAdministrationDateDesc(patientId)).thenReturn(List.of());
        assertThat(service.getImmunizationsByPatientId(patientId)).isEmpty();
    }

    @Test
    void getImmunizationsByPatientId_patientNotFound() {
        when(patientRepository.existsById(patientId)).thenReturn(false);
        assertThatThrownBy(() -> service.getImmunizationsByPatientId(patientId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getImmunizationsByVaccineCode_success() {
        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(immunizationRepository.findByPatient_IdAndVaccineCodeOrderByAdministrationDateDesc(patientId, "FLU")).thenReturn(List.of());
        assertThat(service.getImmunizationsByVaccineCode(patientId, "FLU")).isEmpty();
    }

    @Test
    void getOverdueImmunizations_success() {
        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(immunizationRepository.findOverdueImmunizations(patientId)).thenReturn(List.of());
        assertThat(service.getOverdueImmunizations(patientId)).isEmpty();
    }

    @Test
    void getUpcomingImmunizations_success() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusDays(30);
        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(immunizationRepository.findUpcomingDueDates(patientId, start, end)).thenReturn(List.of());
        assertThat(service.getUpcomingImmunizations(patientId, start, end)).isEmpty();
    }

    @Test
    void getImmunizationsNeedingReminders_success() {
        LocalDate reminderDate = LocalDate.now();
        when(patientRepository.existsById(patientId)).thenReturn(true);
        when(immunizationRepository.findImmunizationsNeedingReminders(patientId, reminderDate)).thenReturn(List.of());
        assertThat(service.getImmunizationsNeedingReminders(patientId, reminderDate)).isEmpty();
    }

    @Test
    void markReminderSent_success() {
        PatientImmunization entity = new PatientImmunization();
        entity.setId(immunizationId);
        when(immunizationRepository.findById(immunizationId)).thenReturn(Optional.of(entity));
        when(immunizationRepository.save(entity)).thenReturn(entity);

        service.markReminderSent(immunizationId);

        assertThat(entity.getReminderSent()).isTrue();
        assertThat(entity.getReminderSentDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void markReminderSent_notFound() {
        when(immunizationRepository.findById(immunizationId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.markReminderSent(immunizationId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateImmunization_success() {
        PatientImmunization existing = new PatientImmunization();
        existing.setId(immunizationId);
        ImmunizationRequestDTO dto = new ImmunizationRequestDTO();
        ImmunizationResponseDTO response = new ImmunizationResponseDTO();

        when(immunizationRepository.findById(immunizationId)).thenReturn(Optional.of(existing));
        when(immunizationRepository.save(existing)).thenReturn(existing);
        when(immunizationMapper.toResponseDTO(existing)).thenReturn(response);

        assertThat(service.updateImmunization(immunizationId, dto)).isEqualTo(response);
        verify(immunizationMapper).updateEntity(existing, dto);
    }

    @Test
    void deleteImmunization_softDeletes() {
        PatientImmunization entity = new PatientImmunization();
        entity.setId(immunizationId);
        when(immunizationRepository.findById(immunizationId)).thenReturn(Optional.of(entity));
        when(immunizationRepository.save(entity)).thenReturn(entity);

        service.deleteImmunization(immunizationId);

        assertThat(entity.getActive()).isFalse();
    }

    @Test
    void deleteImmunization_notFound() {
        when(immunizationRepository.findById(immunizationId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deleteImmunization(immunizationId)).isInstanceOf(ResourceNotFoundException.class);
    }
}
