package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.MedicationCatalogItemMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogItemRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogItemResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.MedicationCatalogItemService;
import com.example.hms.terminology.TerminologyCodes;
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
public class MedicationCatalogItemServiceImpl implements MedicationCatalogItemService {

    private static final String MEDICATION_CATALOG_NOT_FOUND = "medication.catalog.notfound";
    private static final String AUDIT_ENTITY = "MEDICATION_CATALOG_ITEM";

    private final MedicationCatalogItemRepository catalogRepository;
    private final HospitalRepository hospitalRepository;
    private final MedicationCatalogItemMapper mapper;
    private final AuditEventLogService auditEventLogService;
    private final RoleValidator roleValidator;

    @Override
    public MedicationCatalogItemResponseDTO create(MedicationCatalogItemRequestDTO dto) {
        Hospital hospital = hospitalRepository.findById(dto.getHospitalId())
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));

        validateAtcCode(dto.getAtcCode());
        validateRxNormCode(dto.getRxnormCode());

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
                .orElseThrow(() -> new ResourceNotFoundException(MEDICATION_CATALOG_NOT_FOUND));
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
                .orElseThrow(() -> new ResourceNotFoundException(MEDICATION_CATALOG_NOT_FOUND));

        validateAtcCode(dto.getAtcCode());
        validateRxNormCode(dto.getRxnormCode());

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
                .orElseThrow(() -> new ResourceNotFoundException(MEDICATION_CATALOG_NOT_FOUND));
        item.setActive(false);
        catalogRepository.save(item);
        log.info("Deactivated medication catalog item '{}'", item.getNameFr());

        // P-04: formulary deactivation is a governance event — emit a distinct audit
        // record so admin actions are traceable and queryable.
        logAudit(AuditEventType.MEDICATION_DEACTIVATED,
                "Deactivated medication catalog item '" + item.getNameFr() + "'",
                item.getId().toString());
    }

    /**
     * ATC binding is optional, but when supplied it must match the WHO
     * 7-character anatomical-therapeutic-chemical pattern (e.g.
     * {@code J01CA04}) so the FHIR MedicationRequest coding emitted via
     * the catalog stays trustworthy for OpenHIE/DHIS2 downstream nodes.
     */
    private static void validateAtcCode(String atcCode) {
        if (atcCode == null || atcCode.isBlank()) return;
        if (!TerminologyCodes.isValidAtc(atcCode)) {
            throw new IllegalArgumentException(
                "atcCode must match WHO ATC format L##LL## (e.g. J01CA04)");
        }
    }

    private static void validateRxNormCode(String rxNormCode) {
        if (rxNormCode == null || rxNormCode.isBlank()) return;
        if (!TerminologyCodes.isValidRxNorm(rxNormCode)) {
            throw new IllegalArgumentException(
                "rxnormCode must be 1–12 digits (RxCUI numeric only)");
        }
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
