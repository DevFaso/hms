package com.example.hms.service.imaging;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.PriceListItem;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingCatalogueExamDTO;
import com.example.hms.payload.dto.imaging.ImagingCatalogueExamUpdateRequestDTO;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.billing.PriceListLinks;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * QA D3450 (High) and D3254 (r70, V361): the hospital's imaging catalogue.
 *
 * <p>An imaging order used to name its exam in free text. Two defects came from that: a family proxy read
 * « Échographie du 1er trimestre » because no word of it was on the rule's list (D3450), and the line billed 0 F
 * because the wording did not match the price key (D3254). The exam is now picked from the catalogue: the active
 * IMAGING lines of the hospital's price list, each with its code, its name, its modality (from its price-list link)
 * and its body region. The order keeps the item it was picked from, so it is priced by that item and a proxy's read
 * is decided by the item's pregnancy flag ({@link PregnancyRevealingStudyRule}). Free text stays possible, for an
 * exam the catalogue does not hold yet; it is then read by the rule's words and its fail-closed band.
 *
 * <p><b>Who.</b> Roles count where they are held ({@link ImagingRoleScope}, QA D2915): the catalogue is read by
 * whoever reads imaging at the acting hospital; the pregnancy flag and the body region are set by a hospital
 * admin or a radiologist OF that hospital. The exam's code, name, link and price stay with the price list's
 * maintainers ({@code PriceListService}), which puts the flag back to « not decided » when the exam is renamed.
 *
 * <p><b>Tenancy.</b> Every read and write names the acting hospital (pinned, never the global view); an item of
 * another hospital answers as a missing one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImagingCatalogueService {

    static final String FIELD_CATALOGUE_ITEM = "catalogueItemId";
    static final String UNKNOWN_EXAM_KEY = "imaging.catalogue.examUnknown";
    static final String EDITORS_ONLY_KEY = "imaging.catalogue.editorsOnly";
    static final String NOT_FOUND_KEY = "imaging.catalogue.examNotFound";
    static final String REGION_SIZE_KEY = "imaging.catalogue.bodyRegion.size";
    static final String FLAG_REQUIRED_KEY = "imaging.catalogue.pregnancyFlag.required";
    static final String AUDIT_ENTITY = "IMAGING_CATALOGUE_EXAM";
    static final int TEXT_MAX = 150;

    private final PriceListItemRepository itemRepository;
    private final UserRepository userRepository;
    private final RoleValidator roleValidator;
    private final ActingScopeResolver actingScopeResolver;
    private final AuditEventLogService auditEventLogService;
    /** When a decision was taken: the application's clock, as the price list stamps its own changes. */
    private final Clock clock;

    /** The acting hospital's exams, by name. */
    @Transactional(readOnly = true)
    public List<ImagingCatalogueExamDTO> list() {
        UUID hospitalId = actingScopeResolver.requirePinned();
        new ImagingRoleScope(roleValidator).readerAt(hospitalId);
        List<PriceListItem> items = itemRepository.findActiveImagingCatalogue(hospitalId);
        boolean canEdit = editsAt(hospitalId);
        Map<UUID, String> names = namesOf(items);
        return items.stream().map(item -> toDto(item, names, canEdit)).toList();
    }

    /** Sets an exam's pregnancy flag and body region; a hospital admin or a radiologist of the acting hospital. */
    @Transactional
    public ImagingCatalogueExamDTO update(UUID itemId, ImagingCatalogueExamUpdateRequestDTO request) {
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID userId = roleValidator.getCurrentUserId();
        if (!editsAt(hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(EDITORS_ONLY_KEY));
        }
        PriceListItem item = itemRepository.lockByIdAtHospital(itemId, hospitalId)
            .filter(found -> found.getCategory() == PriceListCategory.IMAGING)
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_KEY, itemId));
        requireNotWithdrawn(item);
        // The answer is required (« not decided » is one of its values): an empty request changes nothing.
        ImagingCatalogueExamUpdateRequestDTO.PregnancyFlag answer = request == null ? null : request.getPregnancyFlag();
        if (answer == null) {
            throw new FieldBusinessException("pregnancyFlag", FLAG_REQUIRED_KEY);
        }
        boolean decided = answer != ImagingCatalogueExamUpdateRequestDTO.PregnancyFlag.UNDECIDED;
        Boolean flag = decided ? answer == ImagingCatalogueExamUpdateRequestDTO.PregnancyFlag.YES : null;
        String region = blankToNull(request.getBodyRegion());
        if (region != null && region.length() > TEXT_MAX) {
            throw new FieldBusinessException("bodyRegion", REGION_SIZE_KEY);
        }
        Boolean flagBefore = item.getMayRevealPregnancy();
        boolean flagChanged = !Objects.equals(flagBefore, flag);
        boolean regionChanged = !Objects.equals(item.getImagingBodyRegion(), region);
        if (!flagChanged && !regionChanged) {
            return toDto(item, namesOf(List.of(item)), true);
        }
        item.setImagingBodyRegion(region);
        if (flagChanged) {
            item.setMayRevealPregnancy(flag);
            // Who decided; nobody when the exam is put back to « not decided ».
            item.setMayRevealPregnancySetBy(decided ? userId : null);
            item.setMayRevealPregnancySetAt(decided ? LocalDateTime.now(clock) : null);
        }
        item.setUpdatedBy(userId);
        PriceListItem saved = itemRepository.save(item);
        auditAfterCommit(saved, hospitalId, userId, flagBefore, flagChanged, regionChanged);
        return toDto(saved, namesOf(List.of(saved)), true);
    }

    /**
     * Writes the catalogue exam on an order being placed or edited at {@code hospitalId}: the item, its code, its
     * name as the study type, and its modality and body region when it has them. With no item named, the order is
     * a free-text one and carries none.
     *
     * @throws FieldBusinessException on {@code catalogueItemId} when the item is not an active imaging exam of
     *     this hospital (another hospital's item answers the same way)
     */
    @Transactional(readOnly = true)
    public void applyTo(ImagingOrder order, UUID catalogueItemId, UUID hospitalId) {
        if (catalogueItemId == null) {
            // A free-text order: it still takes the catalogue exam it names word for word (an order set, an older
            // client, a clinician who typed the price list's own label: QA D3254's « Radiographie du thorax
            // (face) »), when exactly one exam has that name. Anything else stays free text.
            PriceListItem named = examNamed(order.getStudyType(), order.getModality(), hospitalId);
            order.setCatalogueItemId(named == null ? null : named.getId());
            order.setCatalogueCode(named == null ? null : named.getCode());
            return;
        }
        PriceListItem item = hospitalId == null ? null
            : itemRepository.findByIdAndHospitalId(catalogueItemId, hospitalId)
                .filter(ImagingCatalogueService::isCatalogueExam)
                .orElse(null);
        if (item == null) {
            throw new FieldBusinessException(FIELD_CATALOGUE_ITEM, UNKNOWN_EXAM_KEY);
        }
        order.setCatalogueItemId(item.getId());
        order.setCatalogueCode(item.getCode());
        String label = item.getLabel() == null ? "" : item.getLabel().trim();
        order.setStudyType(label.length() > TEXT_MAX ? label.substring(0, TEXT_MAX) : label);
        ImagingModality modality = modalityOf(item);
        if (modality != null) {
            order.setModality(modality);
        }
        if (blankToNull(order.getBodyRegion()) == null && item.getImagingBodyRegion() != null) {
            order.setBodyRegion(item.getImagingBodyRegion());
        }
    }

    /**
     * What the catalogue says of each order's exam, for a family proxy's read: by order id, absent for a free-text
     * order, for an exam nobody decided and for an item that cannot be read. Read at each order's OWN hospital,
     * outside the caller's tenant scope: a proxy holds no assignment where the patient was imaged (the QA D1212
     * trap, as {@code ProxyRecordFilter} reads an invoice's items).
     *
     * <p>The flag is the item's as it is TODAY, and an order keeps the name it was placed with. A flagged exam
     * counts for every order that names it (it only hides). A cleared exam counts only for an order still named
     * as the exam is now: an « Échographie obstétricale » ordered before the line was renamed and cleared as
     * something else is not cleared by that decision; it is read as undecided, by its own words.
     */
    @Transactional(readOnly = true)
    public Map<UUID, PregnancyRevealingStudyRule.ExamDecision> pregnancyFlagsOf(List<ImagingOrder> orders) {
        Map<UUID, Set<UUID>> itemsByHospital = new HashMap<>();
        for (ImagingOrder order : orders) {
            UUID hospitalId = hospitalIdOf(order);
            if (order.getCatalogueItemId() != null && hospitalId != null) {
                itemsByHospital.computeIfAbsent(hospitalId, id -> new HashSet<>()).add(order.getCatalogueItemId());
            }
        }
        Map<String, PriceListItem> decided = new HashMap<>();
        itemsByHospital.forEach((hospitalId, ids) ->
            itemRepository.findUnscopedByHospitalIdAndIdIn(hospitalId, ids).stream()
                .filter(item -> item.getMayRevealPregnancy() != null && item.getCategory() == PriceListCategory.IMAGING)
                .forEach(item -> decided.put(hospitalId + "|" + item.getId(), item)));
        Map<UUID, PregnancyRevealingStudyRule.ExamDecision> byOrder = new HashMap<>();
        for (ImagingOrder order : orders) {
            PriceListItem item = order.getCatalogueItemId() == null ? null
                : decided.get(hospitalIdOf(order) + "|" + order.getCatalogueItemId());
            if (item != null && (Boolean.TRUE.equals(item.getMayRevealPregnancy()) || namedAs(order, item))) {
                byOrder.put(order.getId(), new PregnancyRevealingStudyRule.ExamDecision(
                    item.getMayRevealPregnancy(), item.getImagingBodyRegion()));
            }
        }
        return byOrder;
    }

    /** Whether the order still carries the exam's current name (as {@link #applyTo} writes it), case and accents aside. */
    private static boolean namedAs(ImagingOrder order, PriceListItem item) {
        if (order.getStudyType() == null || item.getLabel() == null) {
            return false;
        }
        String label = item.getLabel().trim();
        String asWritten = label.length() > TEXT_MAX ? label.substring(0, TEXT_MAX) : label;
        return PriceListLinks.fold(order.getStudyType()).equals(PriceListLinks.fold(asWritten));
    }

    /**
     * The one active exam of the hospital whose name (or code) is this text, read without case, accents or
     * repeated spaces, and whose modality is the order's when the exam has one; null when none or several match.
     */
    private PriceListItem examNamed(String studyType, ImagingModality modality, UUID hospitalId) {
        if (hospitalId == null || studyType == null || studyType.isBlank()) {
            return null;
        }
        String typed = PriceListLinks.fold(studyType);
        List<PriceListItem> matches = itemRepository.findActiveImagingCatalogue(hospitalId).stream()
            .filter(item -> item.getLabel() != null
                && (typed.equals(PriceListLinks.fold(item.getLabel())) || typed.equals(PriceListLinks.fold(item.getCode()))))
            .filter(item -> modalityOf(item) == null || modality == null || modalityOf(item) == modality)
            .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /** The modality an exam's price-list link names ({@code <MODALITY>:<study>}); null when it names none. */
    public static ImagingModality modalityOf(PriceListItem item) {
        if (item.getLinkType() != PriceListLinkType.IMAGING_STUDY || item.getLinkKey() == null) {
            return null;
        }
        int colon = item.getLinkKey().indexOf(':');
        if (colon <= 0) {
            return null;
        }
        try {
            return ImagingModality.valueOf(item.getLinkKey().substring(0, colon));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /** A deactivated exam is withdrawn from the catalogue: it answers as a missing one, and is never edited. */
    private static void requireNotWithdrawn(PriceListItem item) {
        if (!item.isActive()) {
            throw new ResourceNotFoundException(NOT_FOUND_KEY, item.getId());
        }
    }

    private static boolean isCatalogueExam(PriceListItem item) {
        return item.isActive() && item.getCategory() == PriceListCategory.IMAGING;
    }

    private static UUID hospitalIdOf(ImagingOrder order) {
        return order.getHospital() == null ? null : order.getHospital().getId();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean editsAt(UUID hospitalId) {
        UUID userId = roleValidator.getCurrentUserId();
        return userId != null && hospitalId != null
            && (roleValidator.isHospitalAdmin(userId, hospitalId)
                || roleValidator.hasRole(userId, hospitalId, ImagingRoleScope.RADIOLOGIST_ROLE));
    }

    private Map<UUID, String> namesOf(List<PriceListItem> items) {
        Set<UUID> ids = items.stream().map(PriceListItem::getMayRevealPregnancySetBy)
            .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        userRepository.findAllById(ids).forEach(user -> names.put(user.getId(), displayName(user)));
        return names;
    }

    private static String displayName(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }

    private static ImagingCatalogueExamDTO toDto(PriceListItem item, Map<UUID, String> names, boolean canEdit) {
        return new ImagingCatalogueExamDTO(item.getId(), item.getCode(), item.getLabel(), modalityOf(item),
            item.getImagingBodyRegion(), item.getMayRevealPregnancy(),
            PregnancyRevealingStudyRule.namesPregnancyStudy(item.getLabel(), item.getImagingBodyRegion()),
            item.getMayRevealPregnancySetBy() == null ? null : names.get(item.getMayRevealPregnancySetBy()),
            item.getMayRevealPregnancySetAt(), canEdit);
    }

    /** Ids and the new values only: the exam's name is on the price list, not in the audit trail. */
    private void auditAfterCommit(PriceListItem item, UUID hospitalId, UUID userId, Boolean flagBefore,
                                  boolean flagChanged, boolean regionChanged) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("itemId", String.valueOf(item.getId()));
        details.put("code", String.valueOf(item.getCode()));
        if (flagChanged) {
            details.put("mayRevealPregnancyBefore", String.valueOf(flagBefore));
            details.put("mayRevealPregnancy", String.valueOf(item.getMayRevealPregnancy()));
        }
        details.put("bodyRegionChanged", String.valueOf(regionChanged));
        AuditEventRequestDTO event = AuditEventRequestDTO.builder()
            .eventType(AuditEventType.CONFIGURATION_CHANGED)
            .status(AuditStatus.SUCCESS)
            .userId(userId)
            .hospitalId(hospitalId)
            .entityType(AUDIT_ENTITY)
            .resourceId(String.valueOf(item.getId()))
            .eventDescription("Imaging catalogue exam changed")
            .details(details)
            .build();
        UUID itemId = item.getId();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(event);
            } catch (RuntimeException ex) {
                log.warn("[imaging] catalogue audit failed for item {}: {}", itemId, ex.getClass().getSimpleName());
            }
        });
    }
}
