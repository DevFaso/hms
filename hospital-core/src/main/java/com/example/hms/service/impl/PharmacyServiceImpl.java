package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.PharmacyMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.PharmacyService;
import com.example.hms.utility.RoleValidator;
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

    private static final String PHARMACY_NOT_FOUND = "pharmacy.notfound";
    private static final String AUDIT_ENTITY = "PHARMACY";

    private final PharmacyRepository pharmacyRepository;
    private final HospitalRepository hospitalRepository;
    private final PharmacyMapper mapper;
    private final AuditEventLogService auditEventLogService;
    private final RoleValidator roleValidator;

    @Override
    public PharmacyResponseDTO create(PharmacyRequestDTO dto) {
        Hospital hospital = hospitalRepository.findById(dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));

        Pharmacy entity = mapper.toEntity(dto, hospital);

        Pharmacy saved = pharmacyRepository.save(entity);
        log.info("Created pharmacy '{}' for hospital {}", saved.getName(), hospital.getId());
        return mapper.toResponseDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PharmacyResponseDTO getById(UUID id, UUID hospitalId) {
        Pharmacy pharmacy = pharmacyRepository.findByIdAndHospital_Id(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException(PHARMACY_NOT_FOUND));
        return mapper.toResponseDTO(pharmacy);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PharmacyResponseDTO> listByHospital(UUID hospitalId, Pageable pageable) {
        return pharmacyRepository.findByHospitalIdAndActiveTrue(hospitalId, pageable)
                .map(mapper::toResponseDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PharmacyResponseDTO> search(UUID hospitalId, String query, Pageable pageable) {
        return pharmacyRepository.searchByHospital(hospitalId, query, pageable)
                .map(mapper::toResponseDTO);
    }

    @Override
    public PharmacyResponseDTO update(UUID id, PharmacyRequestDTO dto) {
        Pharmacy existing = pharmacyRepository.findByIdAndHospital_Id(id, dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException(PHARMACY_NOT_FOUND));

        mapper.updateEntity(existing, dto);

        Pharmacy saved = pharmacyRepository.save(existing);
        log.info("Updated pharmacy '{}'", saved.getName());
        return mapper.toResponseDTO(saved);
    }

    @Override
    public void deactivate(UUID id, UUID hospitalId) {
        Pharmacy pharmacy = pharmacyRepository.findByIdAndHospital_Id(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException(PHARMACY_NOT_FOUND));
        pharmacy.setActive(false);
        pharmacyRepository.save(pharmacy);
        log.info("Deactivated pharmacy '{}'", pharmacy.getName());

        // P-04: registry deactivation is a governance event — emit a distinct audit record
        // so admin actions are traceable and queryable.
        logAudit(AuditEventType.PHARMACY_DEACTIVATED,
                "Deactivated pharmacy '" + pharmacy.getName() + "'",
                pharmacy.getId().toString());
    }

    private void logAudit(AuditEventType eventType, String description, String resourceId) {
        try {
            UUID userId = roleValidator.getCurrentUserId();
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(userId)
                    .eventType(eventType)
                    .eventDescription(description)
                    .status(AuditStatus.SUCCESS)
                    .resourceId(resourceId)
                    .entityType(AUDIT_ENTITY)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to log audit event {}: {}", eventType, e.getMessage());
        }
    }
}
