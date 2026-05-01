package com.example.hms.service.integration.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.integration.Dhis2DataElementMappingMapper;
import com.example.hms.mapper.integration.Dhis2FacilityConfigMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.integration.Dhis2DataElementMapping;
import com.example.hms.model.integration.Dhis2FacilityConfig;
import com.example.hms.payload.dto.integration.Dhis2DataElementMappingRequestDTO;
import com.example.hms.payload.dto.integration.Dhis2DataElementMappingResponseDTO;
import com.example.hms.payload.dto.integration.Dhis2FacilityConfigRequestDTO;
import com.example.hms.payload.dto.integration.Dhis2FacilityConfigResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.integration.Dhis2DataElementMappingRepository;
import com.example.hms.repository.integration.Dhis2FacilityConfigRepository;
import com.example.hms.service.integration.Dhis2ConfigService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class Dhis2ConfigServiceImpl implements Dhis2ConfigService {

    private final Dhis2FacilityConfigRepository facilityConfigRepository;
    private final Dhis2DataElementMappingRepository mappingRepository;
    private final HospitalRepository hospitalRepository;
    private final Dhis2FacilityConfigMapper facilityConfigMapper;
    private final Dhis2DataElementMappingMapper mappingMapper;

    public Dhis2ConfigServiceImpl(Dhis2FacilityConfigRepository facilityConfigRepository,
                                  Dhis2DataElementMappingRepository mappingRepository,
                                  HospitalRepository hospitalRepository,
                                  Dhis2FacilityConfigMapper facilityConfigMapper,
                                  Dhis2DataElementMappingMapper mappingMapper) {
        this.facilityConfigRepository = facilityConfigRepository;
        this.mappingRepository = mappingRepository;
        this.hospitalRepository = hospitalRepository;
        this.facilityConfigMapper = facilityConfigMapper;
        this.mappingMapper = mappingMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Dhis2FacilityConfigResponseDTO> getFacilityConfig(UUID hospitalId) {
        return facilityConfigRepository.findByHospital_Id(hospitalId)
            .map(facilityConfigMapper::toResponseDTO);
    }

    @Override
    public Dhis2FacilityConfigResponseDTO upsertFacilityConfig(UUID hospitalId,
                                                               Dhis2FacilityConfigRequestDTO request) {
        final Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Hospital not found: " + hospitalId));

        final Dhis2FacilityConfig saved = facilityConfigRepository.findByHospital_Id(hospitalId)
            .map(existing -> {
                facilityConfigMapper.applyToEntity(request, hospital, existing);
                return facilityConfigRepository.save(existing);
            })
            .orElseGet(() -> facilityConfigRepository.save(
                facilityConfigMapper.toEntity(request, hospital)));

        return facilityConfigMapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Dhis2DataElementMappingResponseDTO> listMappings(UUID hospitalId,
                                                                 String datasetUid,
                                                                 Pageable pageable) {
        return mappingRepository.findByHospital_IdAndDatasetUid(hospitalId, datasetUid, pageable)
            .map(mappingMapper::toResponseDTO);
    }

    @Override
    public Dhis2DataElementMappingResponseDTO createMapping(UUID hospitalId,
                                                            Dhis2DataElementMappingRequestDTO request) {
        final Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Hospital not found: " + hospitalId));
        final Dhis2DataElementMapping saved = mappingRepository.save(
            mappingMapper.toEntity(request, hospital));
        return mappingMapper.toResponseDTO(saved);
    }

    @Override
    public Dhis2DataElementMappingResponseDTO updateMapping(UUID mappingId,
                                                            UUID hospitalId,
                                                            Dhis2DataElementMappingRequestDTO request) {
        final Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Hospital not found: " + hospitalId));
        final Dhis2DataElementMapping existing = mappingRepository.findById(mappingId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "DHIS2 mapping not found: " + mappingId));
        if (!existing.getHospital().getId().equals(hospitalId)) {
            throw new BusinessException(
                "Cross-tenant edit blocked: mapping belongs to another hospital");
        }
        mappingMapper.applyToEntity(request, hospital, existing);
        return mappingMapper.toResponseDTO(mappingRepository.save(existing));
    }

    @Override
    public void deleteMapping(UUID mappingId, UUID hospitalId) {
        final Dhis2DataElementMapping existing = mappingRepository.findById(mappingId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "DHIS2 mapping not found: " + mappingId));
        if (!existing.getHospital().getId().equals(hospitalId)) {
            throw new BusinessException(
                "Cross-tenant delete blocked: mapping belongs to another hospital");
        }
        mappingRepository.delete(existing);
    }
}
