package com.example.hms.service.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.MedicationCatalogItemMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.payload.dto.medication.MedicationCatalogItemRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogItemResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.service.MedicationCatalogItemService;
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
public class MedicationCatalogItemServiceImpl implements MedicationCatalogItemService {

    private final MedicationCatalogItemRepository catalogRepository;
    private final HospitalRepository hospitalRepository;
    private final MedicationCatalogItemMapper mapper;

    @Override
    public MedicationCatalogItemResponseDTO create(MedicationCatalogItemRequestDTO dto) {
        Hospital hospital = hospitalRepository.findById(dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));

        MedicationCatalogItem entity = mapper.toEntity(dto);
        entity.setHospital(hospital);

        MedicationCatalogItem saved = catalogRepository.save(entity);
        log.info("Created medication catalog item '{}' for hospital {}", saved.getNameFr(), hospital.getId());
        return mapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public MedicationCatalogItemResponseDTO getById(UUID id, UUID hospitalId) {
        MedicationCatalogItem item = catalogRepository.findByIdAndHospital_Id(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("medication.catalog.notfound"));
        return mapper.toResponseDTO(item);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MedicationCatalogItemResponseDTO> listByHospital(UUID hospitalId, Pageable pageable) {
        return catalogRepository.findByHospital_IdAndActiveTrue(hospitalId, pageable)
                .map(mapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MedicationCatalogItemResponseDTO> search(UUID hospitalId, String query, Pageable pageable) {
        return catalogRepository.searchByHospital(hospitalId, query, pageable)
                .map(mapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MedicationCatalogItemResponseDTO> listByCategory(UUID hospitalId, String category, Pageable pageable) {
        return catalogRepository.findByHospital_IdAndCategoryAndActiveTrue(hospitalId, category, pageable)
                .map(mapper::toResponseDTO);
    }

    @Override
    public MedicationCatalogItemResponseDTO update(UUID id, MedicationCatalogItemRequestDTO dto) {
        MedicationCatalogItem existing = catalogRepository.findByIdAndHospital_Id(id, dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("medication.catalog.notfound"));

        existing.setNameFr(dto.getNameFr());
        existing.setGenericName(dto.getGenericName());
        existing.setBrandName(dto.getBrandName());
        existing.setAtcCode(dto.getAtcCode());
        existing.setForm(dto.getForm());
        existing.setStrength(dto.getStrength());
        existing.setStrengthUnit(dto.getStrengthUnit());
        existing.setRxnormCode(dto.getRxnormCode());
        existing.setRoute(dto.getRoute());
        existing.setCategory(dto.getCategory());
        existing.setEssentialList(dto.isEssentialList());
        existing.setControlled(dto.isControlled());
        existing.setActive(dto.isActive());
        existing.setDescription(dto.getDescription());

        MedicationCatalogItem saved = catalogRepository.save(existing);
        log.info("Updated medication catalog item '{}'", saved.getNameFr());
        return mapper.toResponseDTO(saved);
    }

    @Override
    public void deactivate(UUID id, UUID hospitalId) {
        MedicationCatalogItem item = catalogRepository.findByIdAndHospital_Id(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("medication.catalog.notfound"));
        item.setActive(false);
        catalogRepository.save(item);
        log.info("Deactivated medication catalog item '{}'", item.getNameFr());
    }
}
