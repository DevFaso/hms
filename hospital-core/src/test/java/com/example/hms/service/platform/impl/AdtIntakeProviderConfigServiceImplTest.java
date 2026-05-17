package com.example.hms.service.platform.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.AdmissionType;
import com.example.hms.enums.EncounterType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.platform.AdtIntakeProviderConfig;
import com.example.hms.payload.dto.platform.AdtIntakeProviderConfigRequestDTO;
import com.example.hms.payload.dto.platform.AdtIntakeProviderConfigResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.platform.AdtIntakeProviderConfigRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdtIntakeProviderConfigServiceImplTest {

    @Mock private AdtIntakeProviderConfigRepository repository;
    @Mock private HospitalRepository hospitalRepository;

    @InjectMocks private AdtIntakeProviderConfigServiceImpl service;

    private Hospital hospital;
    private UUID hospitalId;
    private UUID providerId;
    private UUID departmentId;
    private UUID assignmentId;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        providerId = UUID.randomUUID();
        departmentId = UUID.randomUUID();
        assignmentId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Test Hospital");
    }

    private AdtIntakeProviderConfigRequestDTO sampleRequest(Boolean enabled, String chiefComplaint) {
        return new AdtIntakeProviderConfigRequestDTO(
            hospitalId,
            providerId,
            departmentId,
            assignmentId,
            AdmissionType.EMERGENCY,
            AcuityLevel.LEVEL_2_MODERATE,
            EncounterType.INPATIENT,
            chiefComplaint,
            enabled);
    }

    @Test
    @DisplayName("upsert creates a new config when none exists for the hospital")
    void upsertCreatesWhenAbsent() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(repository.findByHospital_Id(hospitalId)).thenReturn(Optional.empty());
        when(repository.save(any(AdtIntakeProviderConfig.class)))
            .thenAnswer(invocation -> {
                AdtIntakeProviderConfig saved = invocation.getArgument(0);
                saved.setId(UUID.randomUUID());
                return saved;
            });

        AdtIntakeProviderConfigResponseDTO response =
            service.upsert(sampleRequest(true, "Walk-in admission"), Locale.ENGLISH);

        ArgumentCaptor<AdtIntakeProviderConfig> saved =
            ArgumentCaptor.forClass(AdtIntakeProviderConfig.class);
        verify(repository).save(saved.capture());
        AdtIntakeProviderConfig entity = saved.getValue();
        assertThat(entity.getHospital()).isSameAs(hospital);
        assertThat(entity.getAdmittingProviderId()).isEqualTo(providerId);
        assertThat(entity.getDepartmentId()).isEqualTo(departmentId);
        assertThat(entity.getDefaultAssignmentId()).isEqualTo(assignmentId);
        assertThat(entity.getDefaultAdmissionType()).isEqualTo(AdmissionType.EMERGENCY);
        assertThat(entity.getDefaultAcuityLevel()).isEqualTo(AcuityLevel.LEVEL_2_MODERATE);
        assertThat(entity.getDefaultEncounterType()).isEqualTo(EncounterType.INPATIENT);
        assertThat(entity.getDefaultChiefComplaint()).isEqualTo("Walk-in admission");
        assertThat(entity.isEnabled()).isTrue();

        assertThat(response.hospitalId()).isEqualTo(hospitalId);
        assertThat(response.hospitalName()).isEqualTo("Test Hospital");
        assertThat(response.enabled()).isTrue();
    }

    @Test
    @DisplayName("upsert updates the existing row in-place when the hospital already has a config")
    void upsertUpdatesExisting() {
        AdtIntakeProviderConfig existing = new AdtIntakeProviderConfig();
        existing.setId(UUID.randomUUID());
        existing.setHospital(hospital);
        existing.setEnabled(false);

        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(repository.findByHospital_Id(hospitalId)).thenReturn(Optional.of(existing));
        when(repository.save(any(AdtIntakeProviderConfig.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service.upsert(sampleRequest(true, "Updated complaint"), Locale.ENGLISH);

        ArgumentCaptor<AdtIntakeProviderConfig> saved =
            ArgumentCaptor.forClass(AdtIntakeProviderConfig.class);
        verify(repository).save(saved.capture());
        // Same entity instance reused — service mutates-in-place rather
        // than inserting a duplicate (one-row-per-hospital invariant).
        assertThat(saved.getValue()).isSameAs(existing);
        assertThat(saved.getValue().isEnabled()).isTrue();
        assertThat(saved.getValue().getDefaultChiefComplaint()).isEqualTo("Updated complaint");
    }

    @Test
    @DisplayName("upsert defaults enabled=false when the request omits it (admin must opt in explicitly)")
    void upsertDefaultsEnabledFalseWhenAbsent() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(repository.findByHospital_Id(hospitalId)).thenReturn(Optional.empty());
        when(repository.save(any(AdtIntakeProviderConfig.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service.upsert(sampleRequest(null, "Walk-in"), Locale.ENGLISH);

        ArgumentCaptor<AdtIntakeProviderConfig> saved =
            ArgumentCaptor.forClass(AdtIntakeProviderConfig.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isEnabled())
            .as("missing enabled flag must NOT silently flip auto-create on")
            .isFalse();
    }

    @Test
    @DisplayName("upsert falls back to the canonical default chief complaint when the request is blank")
    void upsertFallsBackToDefaultChiefComplaintWhenBlank() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(repository.findByHospital_Id(hospitalId)).thenReturn(Optional.empty());
        when(repository.save(any(AdtIntakeProviderConfig.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service.upsert(sampleRequest(true, "   "), Locale.ENGLISH);

        ArgumentCaptor<AdtIntakeProviderConfig> saved =
            ArgumentCaptor.forClass(AdtIntakeProviderConfig.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getDefaultChiefComplaint())
            .isEqualTo("Auto-created from ADT^A01");
    }

    @Test
    @DisplayName("upsert throws ResourceNotFoundException when the hospital UUID does not resolve")
    void upsertThrowsWhenHospitalMissing() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(sampleRequest(true, "x"), Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("getById returns the response DTO when the row exists")
    void getByIdReturnsResponse() {
        UUID configId = UUID.randomUUID();
        AdtIntakeProviderConfig entity = new AdtIntakeProviderConfig();
        entity.setId(configId);
        entity.setHospital(hospital);
        entity.setAdmittingProviderId(providerId);
        entity.setDepartmentId(departmentId);
        entity.setDefaultAdmissionType(AdmissionType.ELECTIVE);
        entity.setDefaultAcuityLevel(AcuityLevel.LEVEL_3_MAJOR);
        entity.setDefaultEncounterType(EncounterType.OUTPATIENT);
        entity.setDefaultChiefComplaint("walk-in");
        entity.setEnabled(true);
        when(repository.findById(configId)).thenReturn(Optional.of(entity));

        AdtIntakeProviderConfigResponseDTO response = service.getById(configId, Locale.ENGLISH);

        assertThat(response.id()).isEqualTo(configId);
        assertThat(response.hospitalId()).isEqualTo(hospitalId);
        assertThat(response.hospitalName()).isEqualTo("Test Hospital");
        assertThat(response.defaultAdmissionType()).isEqualTo(AdmissionType.ELECTIVE);
    }

    @Test
    @DisplayName("getById throws ResourceNotFoundException when the row is absent")
    void getByIdThrowsWhenMissing() {
        UUID configId = UUID.randomUUID();
        when(repository.findById(configId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(configId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("findByHospital returns Optional.empty() when no row exists for the hospital")
    void findByHospitalReturnsEmpty() {
        when(repository.findByHospital_Id(hospitalId)).thenReturn(Optional.empty());

        Optional<AdtIntakeProviderConfigResponseDTO> response =
            service.findByHospital(hospitalId);

        assertThat(response).isEmpty();
    }

    @Test
    @DisplayName("findByHospital maps the entity to the response DTO when present")
    void findByHospitalReturnsResponse() {
        AdtIntakeProviderConfig entity = new AdtIntakeProviderConfig();
        entity.setId(UUID.randomUUID());
        entity.setHospital(hospital);
        entity.setAdmittingProviderId(providerId);
        entity.setDefaultAdmissionType(AdmissionType.EMERGENCY);
        entity.setDefaultAcuityLevel(AcuityLevel.LEVEL_2_MODERATE);
        entity.setDefaultEncounterType(EncounterType.INPATIENT);
        entity.setEnabled(true);
        when(repository.findByHospital_Id(hospitalId)).thenReturn(Optional.of(entity));

        Optional<AdtIntakeProviderConfigResponseDTO> response =
            service.findByHospital(hospitalId);

        assertThat(response).isPresent();
        assertThat(response.get().hospitalId()).isEqualTo(hospitalId);
    }

    @Test
    @DisplayName("findAll maps every row through the response mapper")
    void findAllReturnsAllRows() {
        AdtIntakeProviderConfig entity = new AdtIntakeProviderConfig();
        entity.setId(UUID.randomUUID());
        entity.setHospital(hospital);
        entity.setDefaultAdmissionType(AdmissionType.URGENT);
        entity.setDefaultAcuityLevel(AcuityLevel.LEVEL_2_MODERATE);
        entity.setDefaultEncounterType(EncounterType.INPATIENT);
        when(repository.findAll()).thenReturn(List.of(entity));

        List<AdtIntakeProviderConfigResponseDTO> rows = service.findAll();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).hospitalId()).isEqualTo(hospitalId);
    }

    @Test
    @DisplayName("delete removes the row when it exists")
    void deleteRemovesExistingRow() {
        UUID configId = UUID.randomUUID();
        AdtIntakeProviderConfig entity = new AdtIntakeProviderConfig();
        entity.setId(configId);
        entity.setHospital(hospital);
        when(repository.findById(configId)).thenReturn(Optional.of(entity));

        service.delete(configId, Locale.ENGLISH);

        verify(repository).delete(entity);
    }

    @Test
    @DisplayName("delete tolerates a null hospital reference on the row (data-rebuild window)")
    void deleteTolerantOfNullHospital() {
        UUID configId = UUID.randomUUID();
        AdtIntakeProviderConfig entity = new AdtIntakeProviderConfig();
        entity.setId(configId);
        entity.setHospital(null);
        when(repository.findById(configId)).thenReturn(Optional.of(entity));

        service.delete(configId, Locale.ENGLISH);

        verify(repository).delete(entity);
    }

    @Test
    @DisplayName("delete throws ResourceNotFoundException when the row is absent")
    void deleteThrowsWhenMissing() {
        UUID configId = UUID.randomUUID();
        when(repository.findById(configId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(configId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).delete(any());
    }
}
