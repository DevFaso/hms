package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
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
        // V95 platform-catalog semantics:
        //   - super-admin: hospitalId optional. null  → global / LNME entry visible
        //                  to every tenant. non-null → tenant-specific entry attached
        //                  to that hospital (e.g. an SKU only one site stocks).
        //   - hospital admin: hospitalId required and must equal their JWT scope.
        //                  An admin from Hospital A cannot insert a medication into
        //                  Hospital B's catalog, and cannot mint global entries —
        //                  global creation is a platform governance act.
        Hospital hospital = resolveCreateHospital(dto.getHospitalId());

        dto.setAtcCode(TerminologyCodes.normalizeAndRequireValidAtc(dto.getAtcCode()));
        dto.setRxnormCode(TerminologyCodes.normalizeAndRequireValidRxNorm(dto.getRxnormCode()));

        MedicationCatalogItem entity = mapper.toEntity(dto);
        entity.setHospital(hospital);

        MedicationCatalogItem saved = catalogRepository.save(entity);
        if (hospital == null) {
            log.info("Created global medication catalog item '{}'", saved.getNameFr());
        } else {
            log.info("Created medication catalog item '{}' for hospital {}",
                saved.getNameFr(), hospital.getId());
        }
        return mapper.toResponseDTO(saved);
    }

    /**
     * Resolve the {@link Hospital} association for a create request,
     * applying the platform-catalog authorization matrix described on
     * {@link #create(MedicationCatalogItemRequestDTO)}. Returns {@code null}
     * for a platform / global entry (super-admin only).
     *
     * <p>Uses {@link RoleValidator#isSuperAdminFromJwtClaim()} rather than
     * the authority-based check because minting a global catalog row is a
     * cross-tenant governance act: the JWT-claim signal is set only by the
     * token issuer when the user is a real super-admin and is not
     * influenced by per-request authority inflation (e.g. a token whose
     * roles claim was forged before signature verification would carry the
     * authority but lack the {@code isSuperAdmin} claim). Same reasoning
     * applies to the cross-tenant write guard in the second branch.
     */
    private Hospital resolveCreateHospital(UUID requestedHospitalId) {
        boolean superAdmin = roleValidator.isSuperAdminFromJwtClaim();
        if (requestedHospitalId == null) {
            if (!superAdmin) {
                throw new BusinessException(
                    "Hospital ID is required. Only super-admins may create global "
                        + "(platform-wide) medication catalog entries.");
            }
            return null;
        }
        Hospital hospital = hospitalRepository.findById(requestedHospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound"));
        if (!superAdmin) {
            UUID activeScope = roleValidator.requireActiveHospitalId();
            if (!hospital.getId().equals(activeScope)) {
                throw new BusinessException(
                    "Hospital admins can only add medications to their own hospital's catalog.");
            }
        }
        return hospital;
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
        // hospitalId may be null (super-admin global view) — same pattern as
        // PharmacyServiceImpl.listByHospital (see comment there).
        return catalogRepository.findActivePage(hospitalId, pageable)
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

        dto.setAtcCode(TerminologyCodes.normalizeAndRequireValidAtc(dto.getAtcCode()));
        dto.setRxnormCode(TerminologyCodes.normalizeAndRequireValidRxNorm(dto.getRxnormCode()));

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
