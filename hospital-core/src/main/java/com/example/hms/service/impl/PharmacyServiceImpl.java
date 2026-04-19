package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.PharmacyMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.payload.dto.pharmacy.PharmacyRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PharmacyRepository;
import com.example.hms.service.PharmacyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PharmacyServiceImpl implements PharmacyService {

    private final PharmacyRepository pharmacyRepository;
    private final HospitalRepository hospitalRepository;
    private final PharmacyMapper mapper;

    @Override
    public PharmacyResponseDTO create(PharmacyRequestDTO dto) {
        Hospital hospital = hospitalRepository.findById(dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));

        Pharmacy entity = mapper.toEntity(dto);
        entity.setHospital(hospital);

        Pharmacy saved = pharmacyRepository.save(entity);
        log.info("Created pharmacy '{}' for hospital {}", saved.getName(), hospital.getId());
        return mapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PharmacyResponseDTO getById(UUID id, UUID hospitalId) {
        Pharmacy pharmacy = pharmacyRepository.findByIdAndHospital_Id(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notfound"));
        return mapper.toResponseDTO(pharmacy);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PharmacyResponseDTO> listByHospital(UUID hospitalId, Pageable pageable) {
        return pharmacyRepository.findByHospital_IdAndActiveTrue(hospitalId, pageable)
                .map(mapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PharmacyResponseDTO> search(UUID hospitalId, String query, Pageable pageable) {
        return pharmacyRepository.searchByHospital(hospitalId, query, pageable)
                .map(mapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PharmacyResponseDTO> listByTier(UUID hospitalId, int tier, Pageable pageable) {
        return pharmacyRepository.findByHospital_IdAndTierAndActiveTrue(hospitalId, tier, pageable)
                .map(mapper::toResponseDTO);
    }

    @Override
    public PharmacyResponseDTO update(UUID id, PharmacyRequestDTO dto) {
        Pharmacy existing = pharmacyRepository.findByIdAndHospital_Id(id, dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notfound"));

        existing.setName(dto.getName());
        existing.setLicenseNumber(dto.getLicenseNumber());
        existing.setPhone(dto.getPhone());
        existing.setEmail(dto.getEmail());
        existing.setAddressLine1(dto.getAddressLine1());
        existing.setAddressLine2(dto.getAddressLine2());
        existing.setCity(dto.getCity());
        existing.setRegion(dto.getRegion());
        existing.setCountry(dto.getCountry());
        existing.setLatitude(dto.getLatitude());
        existing.setLongitude(dto.getLongitude());
        existing.setFulfillmentMode(dto.getFulfillmentMode());
        existing.setTier(dto.getTier());
        existing.setPartnerAgreement(dto.isPartnerAgreement());
        existing.setPartnerContact(dto.getPartnerContact());
        existing.setExchangeMethod(dto.getExchangeMethod());
        existing.setActive(dto.isActive());
        existing.setNotes(dto.getNotes());

        Pharmacy saved = pharmacyRepository.save(existing);
        log.info("Updated pharmacy '{}'", saved.getName());
        return mapper.toResponseDTO(saved);
    }

    @Override
    public void deactivate(UUID id, UUID hospitalId) {
        Pharmacy pharmacy = pharmacyRepository.findByIdAndHospital_Id(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("pharmacy.notfound"));
        pharmacy.setActive(false);
        pharmacyRepository.save(pharmacy);
        log.info("Deactivated pharmacy '{}'", pharmacy.getName());
    }
}
