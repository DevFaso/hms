package com.example.hms.service.integration.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.integration.Dhis2DataElementMappingMapper;
import com.example.hms.mapper.integration.Dhis2FacilityConfigMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.integration.Dhis2AuthMode;
import com.example.hms.model.integration.Dhis2DataElementMapping;
import com.example.hms.model.integration.Dhis2FacilityConfig;
import com.example.hms.model.integration.Dhis2PeriodType;
import com.example.hms.payload.dto.integration.Dhis2DataElementMappingRequestDTO;
import com.example.hms.payload.dto.integration.Dhis2FacilityConfigRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.integration.Dhis2DataElementMappingRepository;
import com.example.hms.repository.integration.Dhis2FacilityConfigRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class Dhis2ConfigServiceImplTest {

    @Mock private Dhis2FacilityConfigRepository facilityConfigRepository;
    @Mock private Dhis2DataElementMappingRepository mappingRepository;
    @Mock private HospitalRepository hospitalRepository;

    private final Dhis2FacilityConfigMapper facilityConfigMapper = new Dhis2FacilityConfigMapper();
    private final Dhis2DataElementMappingMapper mappingMapper = new Dhis2DataElementMappingMapper();

    private Dhis2ConfigServiceImpl service;
    private UUID hospitalId;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        service = new Dhis2ConfigServiceImpl(facilityConfigRepository, mappingRepository,
            hospitalRepository, facilityConfigMapper, mappingMapper);
        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);
    }

    @Test
    @DisplayName("getFacilityConfig returns Optional.empty when no config exists")
    void getFacilityConfigEmpty() {
        when(facilityConfigRepository.findByHospital_Id(hospitalId)).thenReturn(Optional.empty());
        assertThat(service.getFacilityConfig(hospitalId)).isEmpty();
    }

    @Test
    @DisplayName("getFacilityConfig maps the entity to a DTO when present")
    void getFacilityConfigMapsToDto() {
        when(facilityConfigRepository.findByHospital_Id(hospitalId))
            .thenReturn(Optional.of(facility()));
        var dto = service.getFacilityConfig(hospitalId);
        assertThat(dto).isPresent();
        assertThat(dto.get().hospitalId()).isEqualTo(hospitalId);
        assertThat(dto.get().baseUrl()).isEqualTo("https://dhis2.example.org");
    }

    @Test
    @DisplayName("upsertFacilityConfig CREATE: persists a new config when none exists")
    void upsertFacilityCreatePath() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(facilityConfigRepository.findByHospital_Id(hospitalId)).thenReturn(Optional.empty());
        when(facilityConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.upsertFacilityConfig(hospitalId, request());

        assertThat(dto.baseUrl()).isEqualTo("https://dhis2.example.org");
        verify(facilityConfigRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("upsertFacilityConfig UPDATE: modifies existing config in place")
    void upsertFacilityUpdatePath() {
        Dhis2FacilityConfig existing = facility();
        existing.setBaseUrl("https://dhis2.old.org");
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(facilityConfigRepository.findByHospital_Id(hospitalId)).thenReturn(Optional.of(existing));
        when(facilityConfigRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.upsertFacilityConfig(hospitalId, request());

        assertThat(dto.baseUrl()).isEqualTo("https://dhis2.example.org");
        verify(facilityConfigRepository, times(1)).save(existing);
    }

    @Test
    @DisplayName("upsertFacilityConfig throws ResourceNotFoundException on missing hospital")
    void upsertFacilityMissingHospital() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upsertFacilityConfig(hospitalId, request()))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(facilityConfigRepository, never()).save(any());
    }

    @Test
    @DisplayName("createMapping persists a new mapping")
    void createMappingHappyPath() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(mappingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.createMapping(hospitalId, mappingRequest());

        assertThat(dto.dhis2DataElementUid()).isEqualTo("DE000000049");
        verify(mappingRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("updateMapping rejects cross-tenant edit")
    void updateMappingCrossTenantBlocked() {
        Dhis2DataElementMapping existing = mappingRow(UUID.randomUUID()); // different hospital
        UUID mappingId = UUID.randomUUID();
        existing.setId(mappingId);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(mappingRepository.findById(mappingId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() ->
            service.updateMapping(mappingId, hospitalId, mappingRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Cross-tenant");
        verify(mappingRepository, never()).save(any());
    }

    @Test
    @DisplayName("deleteMapping rejects cross-tenant delete")
    void deleteMappingCrossTenantBlocked() {
        Dhis2DataElementMapping existing = mappingRow(UUID.randomUUID());
        UUID mappingId = UUID.randomUUID();
        existing.setId(mappingId);
        when(mappingRepository.findById(mappingId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.deleteMapping(mappingId, hospitalId))
            .isInstanceOf(BusinessException.class);
        verify(mappingRepository, never()).delete(any());
    }

    @Test
    @DisplayName("listMappings returns paged DTOs")
    void listMappingsPaginated() {
        Page<Dhis2DataElementMapping> page = new PageImpl<>(java.util.List.of(mappingRow(hospitalId)));
        when(mappingRepository.findByHospital_IdAndDatasetUid(hospitalId, "DS00000DEFK",
            PageRequest.of(0, 20))).thenReturn(page);

        var result = service.listMappings(hospitalId, "DS00000DEFK", PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).datasetUid()).isEqualTo("DS00000DEFK");
    }

    private Dhis2FacilityConfig facility() {
        return Dhis2FacilityConfig.builder()
            .hospital(hospital)
            .baseUrl("https://dhis2.example.org")
            .authMode(Dhis2AuthMode.PAT)
            .authSecretEnvVar("DHIS2_TOKEN")
            .defaultPeriodType(Dhis2PeriodType.MONTHLY)
            .active(true)
            .build();
    }

    private Dhis2FacilityConfigRequestDTO request() {
        return new Dhis2FacilityConfigRequestDTO(
            "https://dhis2.example.org", Dhis2AuthMode.PAT, "DHIS2_TOKEN",
            Dhis2PeriodType.MONTHLY, "DS00000DEFK", true);
    }

    private Dhis2DataElementMappingRequestDTO mappingRequest() {
        return new Dhis2DataElementMappingRequestDTO(
            "http://hl7.org/fhir/sid/cvx", "49", "DE000000049", null,
            Dhis2PeriodType.MONTHLY, "DS00000DEFK", true);
    }

    private Dhis2DataElementMapping mappingRow(UUID hid) {
        Hospital h = new Hospital();
        h.setId(hid);
        return Dhis2DataElementMapping.builder()
            .hospital(h)
            .hmsConceptSystem("http://hl7.org/fhir/sid/cvx")
            .hmsConceptCode("49")
            .dhis2DataElementUid("DE000000049")
            .periodType(Dhis2PeriodType.MONTHLY)
            .datasetUid("DS00000DEFK")
            .active(true)
            .build();
    }
}
