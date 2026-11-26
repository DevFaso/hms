package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.MedicationCatalogItemMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.medication.DoseLimitSnapshot;
import com.example.hms.model.medication.FormularySnapshot;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.medication.MedicationCatalogVersion;
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
    private static final String MEDICATION_CATALOG_PLATFORM_ENTRY = "medication.catalog.platformEntry";
    private static final String AUDIT_ENTITY = "MEDICATION_CATALOG_ITEM";

    private final MedicationCatalogItemRepository catalogRepository;
    private final HospitalRepository hospitalRepository;
    private final MedicationCatalogItemMapper mapper;
    private final AuditEventLogService auditEventLogService;
    private final RoleValidator roleValidator;
    /** Go-live D2 (V385): what prescribing and dispensing read changes through an approved version only. */
    private final com.example.hms.service.formulary.MedicationCatalogVersionService versionService;

    /**
     * Go-live D2 (V385): a new entry is PROPOSED here, and is in the catalogue only once a second person approved
     * it ({@code MedicationCatalogVersionService#approve}). Nothing is written to the catalogue by this call.
     */
    @Override
    public com.example.hms.payload.dto.medication.MedicationCatalogVersionResponseDTO create(
            MedicationCatalogItemRequestDTO dto) {
        // V95 platform-catalog semantics:
        //   - super-admin: hospitalId optional. null  → global / LNME entry visible
        //                  to every tenant. non-null → tenant-specific entry attached
        //                  to that hospital (e.g. an SKU only one site stocks).
        //   - hospital admin: hospitalId defaults to the acting hospital and, when sent, must equal it.
        //                  An admin from Hospital A cannot insert a medication into
        //                  Hospital B's catalog, and cannot mint global entries —
        //                  global creation is a platform governance act.
        Hospital hospital = resolveCreateHospital(dto.getHospitalId());

        dto.setAtcCode(TerminologyCodes.normalizeAndRequireValidAtc(dto.getAtcCode()));
        dto.setRxnormCode(TerminologyCodes.normalizeAndRequireValidRxNorm(dto.getRxnormCode()));

        FormularySnapshot proposed = new FormularySnapshot(dto.getNameFr(), dto.getGenericName(), dto.getBrandName(),
            dto.getAtcCode(), dto.getForm(), dto.getStrength(), dto.getStrengthUnit(), dto.getRxnormCode(),
            dto.getTallManName(), dto.getRoute(), dto.isControlled(), dto.isActive(), dto.getCategory(),
            dto.isEssentialList(), dto.getDescription(), dto.getSensitivityCategory());
        MedicationCatalogVersion version = versionService.proposeNewEntry(hospital, proposed, dto.getChangeReason());
        log.info("Proposed new medication catalog item, version {} awaiting approval", version.getId());
        return versionService.toDto(version);
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
        UUID targetHospitalId = requestedHospitalId;
        if (targetHospitalId == null) {
            if (superAdmin) {
                return null;
            }
            // D34: a hospital admin's entry belongs to the hospital they act for;
            // the form need not repeat it. The scope comes from the resolved
            // acting scope, never from the request.
            UUID activeScope = roleValidator.requireActiveHospitalId();
            if (activeScope == null) {
                throw new BusinessException("catalog.hospital.required");
            }
            targetHospitalId = activeScope;
        }
        final UUID lookupId = targetHospitalId;
        Hospital hospital = hospitalRepository.findClinicalById(lookupId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notfound", lookupId));
        if (!superAdmin) {
            UUID activeScope = roleValidator.requireActiveHospitalId();
            if (!hospital.getId().equals(activeScope)) {
                throw new BusinessException("catalog.hospital.otherOwner");
            }
        }
        return hospital;
    }

    @Override
    @Transactional(readOnly = true)
    public MedicationCatalogItemResponseDTO getById(UUID id, UUID hospitalId) {
        MedicationCatalogItem item = catalogRepository.findByIdAndHospital_Id(id, hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException(MEDICATION_CATALOG_NOT_FOUND));
        return withPending(item);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MedicationCatalogItemResponseDTO> listByHospital(UUID hospitalId, Pageable pageable) {
        // hospitalId may be null (super-admin global view) — same pattern as
        // PharmacyServiceImpl.listByHospital (see comment there).
        return withPending(catalogRepository.findActivePage(hospitalId, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MedicationCatalogItemResponseDTO> search(UUID hospitalId, String query, Pageable pageable) {
        // A null scope is the verified super-admin's global view (the controller
        // refuses it for everyone else), searched as the list endpoint lists.
        Page<MedicationCatalogItem> page = hospitalId == null
                ? catalogRepository.searchAllActive(query, pageable)
                : catalogRepository.searchByHospital(hospitalId, query, pageable);
        return withPending(page);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MedicationCatalogItemResponseDTO> listByCategory(UUID hospitalId, String category, Pageable pageable) {
        // A null scope is the verified super-admin's global view, as on list and search.
        Page<MedicationCatalogItem> page = hospitalId == null
                ? catalogRepository.findByCategoryAndActiveTrue(category, pageable)
                : catalogRepository.findByHospital_IdAndCategoryAndActiveTrue(hospitalId, category, pageable);
        return withPending(page);
    }

    @Override
    public MedicationCatalogItemResponseDTO update(UUID id, MedicationCatalogItemRequestDTO dto) {
        MedicationCatalogItem existing = requireWritable(id, dto.getHospitalId());

        dto.setAtcCode(TerminologyCodes.normalizeAndRequireValidAtc(dto.getAtcCode()));
        dto.setRxnormCode(TerminologyCodes.normalizeAndRequireValidRxNorm(dto.getRxnormCode()));

        // Go-live D2 (V385): what prescribing and dispensing read (names, ATC, form, strength, route, RxNorm,
        // controlled, putting a withdrawn entry back) is never written here. A difference becomes a version
        // awaiting approval and the row keeps the values in force. The rest is edited directly, at once.
        boolean withdrawn = existing.isActive() && !dto.isActive();
        if (withdrawn) {
            existing.deactivate();
        }
        FormularySnapshot live = FormularySnapshot.of(existing);
        FormularySnapshot proposed = new FormularySnapshot(dto.getNameFr(), dto.getGenericName(), dto.getBrandName(),
            dto.getAtcCode(), dto.getForm(), dto.getStrength(), dto.getStrengthUnit(), dto.getRxnormCode(),
            // The editor does not carry the tall-man name: left out, it stays.
            dto.getTallManName() != null ? dto.getTallManName() : existing.getTallManName(),
            dto.getRoute(), dto.isControlled(), dto.isActive(), live.category(), live.essentialList(),
            live.description(), live.sensitivityCategory());
        if (!proposed.clinicalDifferences(live).isEmpty()) {
            versionService.proposeFormulary(existing, proposed, dto.getChangeReason());
        }
        existing.setCategory(dto.getCategory());
        existing.setEssentialList(dto.isEssentialList());
        existing.setDescription(dto.getDescription());
        // Policy 2026-11-20: the tag a proxy's read obeys. Left out, it stays (this edit replaces
        // every other field); a change, or the explicit clear, is on the audit trail.
        com.example.hms.enums.SensitivityCategory tagBefore = existing.getSensitivityCategory();
        com.example.hms.enums.SensitivityCategory tagAfter = tagBefore;
        if (dto.getSensitivityCategory() != null) {
            tagAfter = dto.getSensitivityCategory();
        } else if (Boolean.TRUE.equals(dto.getClearSensitivityCategory())) {
            tagAfter = null;
        }
        existing.setSensitivityCategory(tagAfter);

        MedicationCatalogItem saved = catalogRepository.save(existing);
        log.info("Updated medication catalog item '{}'", saved.getNameFr());
        if (tagAfter != tagBefore) {
            // Category names only, never the medicine's name next to them.
            logAudit(AuditEventType.CONFIGURATION_CHANGED,
                "Medication sensitivity category changed from " + tagBefore + " to " + tagAfter,
                saved.getId().toString());
        }
        if (withdrawn) {
            logAudit(AuditEventType.MEDICATION_DEACTIVATED,
                "Deactivated medication catalog item '" + saved.getNameFr() + "'", saved.getId().toString());
        }
        return withPending(saved);
    }

    /**
     * Go-live D2 (V385): an entry no second person approved yet (it predates the circuit) is submitted as it
     * stands, so that it gets its approval without a pretend change.
     */
    @Override
    public MedicationCatalogItemResponseDTO requestFormularyApproval(UUID id, String reason) {
        MedicationCatalogItem existing = requireWritable(id, null);
        versionService.proposeFormulary(existing, FormularySnapshot.of(existing), reason);
        return withPending(existing);
    }

    /** The entry as the API shows it, with the versions awaiting a decision on it. */
    private MedicationCatalogItemResponseDTO withPending(MedicationCatalogItem item) {
        MedicationCatalogItemResponseDTO dto = mapper.toResponseDTO(item);
        if (dto != null && item.getId() != null) {
            setPending(dto, versionService.pendingByEntry(java.util.List.of(item.getId())));
        }
        return dto;
    }

    private Page<MedicationCatalogItemResponseDTO> withPending(Page<MedicationCatalogItem> page) {
        java.util.Map<UUID, java.util.Map<com.example.hms.enums.MedicationCatalogVersionKind, UUID>> pending =
            versionService.pendingByEntry(page.getContent().stream().map(MedicationCatalogItem::getId).toList());
        return page.map(item -> {
            MedicationCatalogItemResponseDTO dto = mapper.toResponseDTO(item);
            if (dto != null) {
                setPending(dto, pending);
            }
            return dto;
        });
    }

    private static void setPending(MedicationCatalogItemResponseDTO dto,
            java.util.Map<UUID, java.util.Map<com.example.hms.enums.MedicationCatalogVersionKind, UUID>> pending) {
        java.util.Map<com.example.hms.enums.MedicationCatalogVersionKind, UUID> mine = pending.get(dto.getId());
        if (mine != null) {
            dto.setPendingFormularyVersionId(mine.get(com.example.hms.enums.MedicationCatalogVersionKind.FORMULARY));
            dto.setPendingDoseLimitVersionId(mine.get(com.example.hms.enums.MedicationCatalogVersionKind.DOSE_LIMIT));
        }
    }

    /**
     * r66 D2901: the row a catalogue edit or deactivation acts on. Its hospital is the caller's acting
     * hospital, never a request field: the editor sends no {@code hospitalId}, so the lookup by the body's
     * (null) hospital answered 404 for every save since the editor shipped, and a body that did name one
     * was looked up at whatever hospital it named.
     * <ul>
     *   <li>A hospital's own entry: a caller acting AT that hospital. That holds for a super-admin too:
     *       pinned to hospital A, they do not edit hospital B's entry by its id (review of #1250). Only in
     *       global view (no hospital pinned) does a verified super-admin reach any hospital's entry.</li>
     *   <li>A platform (LNME) entry, shared by every tenant: a verified super-admin only, pinned or not
     *       (it is in every hospital's list). A hospital admin sees it too, so the refusal says why rather
     *       than "not found".</li>
     *   <li>A {@code hospitalId} that is sent must be the entry's own (the API contract before this fix);
     *       the controller narrows the acting scope to it first.</li>
     * </ul>
     * Another hospital's entry answers "not found", as an unknown id does.
     */
    private MedicationCatalogItem requireWritable(UUID id, UUID requestedHospitalId) {
        boolean platformAdmin = roleValidator.isSuperAdminFromJwtClaim();
        MedicationCatalogItem item = catalogRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(MEDICATION_CATALOG_NOT_FOUND));
        UUID owner = item.getHospital() == null ? null : item.getHospital().getId();
        if (owner == null) {
            if (!platformAdmin) {
                throw new BusinessException(MEDICATION_CATALOG_PLATFORM_ENTRY);
            }
            if (requestedHospitalId != null) {
                throw new ResourceNotFoundException(MEDICATION_CATALOG_NOT_FOUND);
            }
            return item;
        }
        if (requestedHospitalId != null && !requestedHospitalId.equals(owner)) {
            throw new ResourceNotFoundException(MEDICATION_CATALOG_NOT_FOUND);
        }
        // Null only for a verified super-admin in global view; everyone else acts at one hospital.
        UUID acting = roleValidator.requireActiveHospitalId();
        boolean reachable = acting == null ? platformAdmin : owner.equals(acting);
        if (!reachable) {
            throw new ResourceNotFoundException(MEDICATION_CATALOG_NOT_FOUND);
        }
        return item;
    }

    @Override
    public void deactivate(UUID id, UUID hospitalId) {
        MedicationCatalogItem item = requireWritable(id, hospitalId);
        item.deactivate();
        catalogRepository.save(item);
        log.info("Deactivated medication catalog item '{}'", item.getNameFr());

        // P-04: formulary deactivation is a governance event — emit a distinct audit
        // record so admin actions are traceable and queryable.
        logAudit(AuditEventType.MEDICATION_DEACTIVATED,
                "Deactivated medication catalog item '" + item.getNameFr() + "'",
                item.getId().toString());
    }

    @Override
    public MedicationCatalogItemResponseDTO updatePediatricDosing(
            UUID id, com.example.hms.payload.dto.medication.PediatricDosingRequestDTO dto) {
        // The scope is the caller's acting hospital, never a request field.
        UUID scope = roleValidator.requireActiveHospitalId();
        MedicationCatalogItem item = (scope != null
                ? catalogRepository.findByIdAndHospital_Id(id, scope)
                : java.util.Optional.<MedicationCatalogItem>empty())
            .orElseThrow(() -> new ResourceNotFoundException(MEDICATION_CATALOG_NOT_FOUND));
        requireConsistentPediatricLimits(dto);

        // Go-live D2 (V385): the limits are PROPOSED. The row keeps the limits in force until a different
        // pharmacist, or the médecin-chef, approves the version; the dose check reads the row and nothing else.
        DoseLimitSnapshot proposed = new DoseLimitSnapshot(dto.getPediatricMaxDoseMgPerKg(),
            dto.getPediatricMaxDailyMgPerKg(), dto.getPediatricMaxSingleDoseMg(), dto.getPediatricMaxDailyMg(),
            dto.getPediatricManualReviewUnderMonths(),
            MedicationCatalogItemMapper.toBands(dto.getPediatricWeightBands()), dto.getPediatricDoseSource());
        versionService.proposeDoseLimits(item, proposed, dto.getChangeReason());
        return withPending(item);
    }

    /** Round 18 (QA D935): limits that contradict each other are refused, field by field. */
    static void requireConsistentPediatricLimits(com.example.hms.payload.dto.medication.PediatricDosingRequestDTO dto) {
        if (dto.getPediatricMaxDoseMgPerKg() != null && dto.getPediatricMaxDailyMgPerKg() != null
                && dto.getPediatricMaxDoseMgPerKg().compareTo(dto.getPediatricMaxDailyMgPerKg()) > 0) {
            throw new com.example.hms.exception.FieldBusinessException("pediatricMaxDoseMgPerKg",
                "medicationCatalogItem.pediatricDosing.perDoseAbovePerDay");
        }
        if (dto.getPediatricMaxSingleDoseMg() != null && dto.getPediatricMaxDailyMg() != null
                && dto.getPediatricMaxSingleDoseMg().compareTo(dto.getPediatricMaxDailyMg()) > 0) {
            throw new com.example.hms.exception.FieldBusinessException("pediatricMaxSingleDoseMg",
                "medicationCatalogItem.pediatricDosing.singleAboveDaily");
        }
        java.util.List<com.example.hms.model.medication.PediatricWeightBand> bands =
            MedicationCatalogItemMapper.toBands(dto.getPediatricWeightBands());
        for (int i = 0; i < bands.size(); i++) {
            com.example.hms.model.medication.PediatricWeightBand band = bands.get(i);
            if (band.getMaxWeightKg() != null && band.getMinWeightKg() != null
                    && band.getMaxWeightKg().compareTo(band.getMinWeightKg()) <= 0) {
                throw new com.example.hms.exception.FieldBusinessException(
                    "pediatricWeightBands[" + i + "].maxWeightKg",
                    "medicationCatalogItem.pediatricDosing.bandInverted");
            }
            if (i > 0) {
                java.math.BigDecimal previousMax = bands.get(i - 1).getMaxWeightKg();
                if (previousMax == null || (band.getMinWeightKg() != null
                        && band.getMinWeightKg().compareTo(previousMax) < 0)) {
                    throw new com.example.hms.exception.FieldBusinessException(
                        "pediatricWeightBands[" + i + "].minWeightKg",
                        "medicationCatalogItem.pediatricDosing.bandOverlap");
                }
            }
        }
    }

    private void logAudit(AuditEventType eventType, String description, String resourceId) {
        try {
            UUID userId = roleValidator.getCurrentUserId();
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(userId)
                    // QA D2924 (r66): the acting hospital anchors the row for the hospital admin's audit log.
                    .hospitalId(roleValidator.getCurrentHospitalId())
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
