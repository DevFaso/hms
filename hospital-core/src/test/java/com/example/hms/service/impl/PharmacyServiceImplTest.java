package com.example.hms.service.impl;

import com.example.hms.enums.PharmacyFulfillmentMode;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PharmacyMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.payload.dto.pharmacy.PharmacyRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PharmacyRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PharmacyServiceImplTest {

    @Mock private PharmacyRepository pharmacyRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PharmacyMapper mapper;

    @InjectMocks private PharmacyServiceImpl service;

    private UUID hospitalId;
    private UUID pharmacyId;
    private Hospital hospital;
    private Pharmacy pharmacy;
    private PharmacyRequestDTO requestDTO;
    private PharmacyResponseDTO responseDTO;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        pharmacyId = UUID.randomUUID();

        hospital = new Hospital();
        ReflectionTestUtils.setField(hospital, "id", hospitalId);

        pharmacy = Pharmacy.builder()
                .name("Central Pharmacy")
                .fulfillmentMode(PharmacyFulfillmentMode.INPATIENT_DISPENSARY)
                .tier(1)
                .hospital(hospital)
                .active(true)
                .build();
        ReflectionTestUtils.setField(pharmacy, "id", pharmacyId);

        requestDTO = PharmacyRequestDTO.builder()
                .name("Central Pharmacy")
                .fulfillmentMode(PharmacyFulfillmentMode.INPATIENT_DISPENSARY)
                .tier(1)
                .hospitalId(hospitalId)
                .build();

        responseDTO = new PharmacyResponseDTO();
        responseDTO.setId(pharmacyId);
        responseDTO.setName("Central Pharmacy");
    }

    // ── create ──

    @Test
    void create_success() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(mapper.toEntity(requestDTO)).thenReturn(pharmacy);
        when(pharmacyRepository.save(any(Pharmacy.class))).thenReturn(pharmacy);
        when(mapper.toResponseDTO(pharmacy)).thenReturn(responseDTO);

        PharmacyResponseDTO result = service.create(requestDTO);

        assertThat(result.getName()).isEqualTo("Central Pharmacy");
        verify(pharmacyRepository).save(any(Pharmacy.class));
    }

    @Test
    void create_hospitalNotFound_throws() {
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(requestDTO))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── getById ──

    @Test
    void getById_success() {
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId))
                .thenReturn(Optional.of(pharmacy));
        when(mapper.toResponseDTO(pharmacy)).thenReturn(responseDTO);

        PharmacyResponseDTO result = service.getById(pharmacyId, hospitalId);

        assertThat(result.getId()).isEqualTo(pharmacyId);
    }

    @Test
    void getById_notFound_throws() {
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(pharmacyId, hospitalId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── listByHospital ──

    @Test
    void listByHospital_returnsPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Pharmacy> page = new PageImpl<>(List.of(pharmacy));
        when(pharmacyRepository.findByHospital_IdAndActiveTrue(hospitalId, pageable)).thenReturn(page);
        when(mapper.toResponseDTO(pharmacy)).thenReturn(responseDTO);

        Page<PharmacyResponseDTO> result = service.listByHospital(hospitalId, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    // ── search ──

    @Test
    void search_returnsPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Pharmacy> page = new PageImpl<>(List.of(pharmacy));
        when(pharmacyRepository.searchByHospital(hospitalId, "Central", pageable)).thenReturn(page);
        when(mapper.toResponseDTO(pharmacy)).thenReturn(responseDTO);

        Page<PharmacyResponseDTO> result = service.search(hospitalId, "Central", pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    // ── listByTier ──

    @Test
    void listByTier_returnsPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Pharmacy> page = new PageImpl<>(List.of(pharmacy));
        when(pharmacyRepository.findByHospital_IdAndTierAndActiveTrue(hospitalId, 1, pageable))
                .thenReturn(page);
        when(mapper.toResponseDTO(pharmacy)).thenReturn(responseDTO);

        Page<PharmacyResponseDTO> result = service.listByTier(hospitalId, 1, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    // ── update ──

    @Test
    void update_success() {
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId))
                .thenReturn(Optional.of(pharmacy));
        when(pharmacyRepository.save(any(Pharmacy.class))).thenReturn(pharmacy);
        when(mapper.toResponseDTO(pharmacy)).thenReturn(responseDTO);

        PharmacyResponseDTO result = service.update(pharmacyId, requestDTO);

        assertThat(result.getName()).isEqualTo("Central Pharmacy");
        verify(pharmacyRepository).save(pharmacy);
    }

    @Test
    void update_notFound_throws() {
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(pharmacyId, requestDTO))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── deactivate ──

    @Test
    void deactivate_success() {
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId))
                .thenReturn(Optional.of(pharmacy));
        when(pharmacyRepository.save(any(Pharmacy.class))).thenReturn(pharmacy);

        service.deactivate(pharmacyId, hospitalId);

        assertThat(pharmacy.isActive()).isFalse();
        verify(pharmacyRepository).save(pharmacy);
    }

    @Test
    void deactivate_notFound_throws() {
        when(pharmacyRepository.findByIdAndHospital_Id(pharmacyId, hospitalId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivate(pharmacyId, hospitalId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
