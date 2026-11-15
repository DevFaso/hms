package com.example.hms.service.billing;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.BloodProductType;
import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.enums.PriceListSystemKey;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.RequiredFields;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.PriceListItem;
import com.example.hms.model.PriceListPrice;
import com.example.hms.model.Treatment;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.billing.PriceListDeactivateRequestDTO;
import com.example.hms.payload.dto.billing.PriceListItemDTO;
import com.example.hms.payload.dto.billing.PriceListItemRequestDTO;
import com.example.hms.payload.dto.billing.PriceListItemUpdateRequestDTO;
import com.example.hms.payload.dto.billing.PriceListLinkOptionsDTO;
import com.example.hms.payload.dto.billing.PriceListPriceDTO;
import com.example.hms.payload.dto.billing.PriceListPriceRequestDTO;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.PriceListPriceRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import com.example.hms.utility.TransactionCallbacks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Billing-money plan PR 2 (V302, scorecard M1): each hospital's price list, with effective-dated price versions.
 *
 * <p><b>Who.</b> Reading: the hospital admin, the accountant, the billing specialist (the till), the receptionist
 * (to quote) and the claims reviewer, at their acting hospital; the verified platform super-admin reads any hospital
 * (one named with X-Hospital-Id, or all of them in global view). Writing: only a HOSPITAL_ADMIN or ACCOUNTANT
 * assignment AT the acting hospital (owner decision D-1, 2026-11-15). The cashier, a clinician, and the super-admin
 * on their own are refused, whatever their authorities say: {@code RoleExpansion} grants a super-admin the hospital
 * admin authority, so the role is checked on the assignment table here, not by {@code @PreAuthorize} alone.
 *
 * <p><b>Tenancy.</b> Every item and version carries its hospital. An item of another hospital answers exactly as a
 * missing one (404); the role is checked before any lookup, so a refusal says nothing about what exists.
 *
 * <p><b>History.</b> A price changes only by adding a version (see {@link PriceVersionRules}); no path edits or
 * deletes one. Posted invoice lines copy their price and are never touched here (D-4).
 *
 * <p><b>Audit.</b> Every write leaves one row after commit, ids only (no label, no price, no reason text).
 */
@Slf4j
@Service
public class PriceListService {

    static final String NOT_FOUND_KEY = "billing.priceList.notFound";
    static final String AUDIT_ENTITY = "PRICE_LIST_ITEM";
    static final int LABEL_MAX = 255;
    static final int REASON_MAX = 500;
    static final int CODE_MAX = 40;
    /** Letters, digits, dot, dash, underscore; starts with a letter or digit (so never a spreadsheet formula). */
    static final Pattern CODE_FORMAT = Pattern.compile("[A-Z0-9][A-Z0-9._-]{0,39}");

    private static final String FIELD_CODE = "code";
    private static final String FIELD_LABEL = "label";
    private static final String FIELD_CATEGORY = "category";
    private static final String FIELD_CHANGE_REASON = "changeReason";
    private static final String FIELD_REASON = "reason";

    private final PriceListItemRepository itemRepository;
    private final PriceListPriceRepository priceRepository;
    private final PriceListLinks links;
    private final LabTestDefinitionRepository labTestDefinitionRepository;
    private final TreatmentRepository treatmentRepository;
    private final UserRepository userRepository;
    private final RoleValidator roleValidator;
    private final ActingScopeResolver actingScopeResolver;
    private final AuditEventLogService auditEventLogService;
    private final Clock clock;
    private final ZoneId businessZone;

    @SuppressWarnings("java:S107") // one collaborator per concern; a parameter object would only hide them
    public PriceListService(PriceListItemRepository itemRepository,
                            PriceListPriceRepository priceRepository,
                            PriceListLinks links,
                            LabTestDefinitionRepository labTestDefinitionRepository,
                            TreatmentRepository treatmentRepository,
                            UserRepository userRepository,
                            RoleValidator roleValidator,
                            ActingScopeResolver actingScopeResolver,
                            AuditEventLogService auditEventLogService,
                            Clock clock,
                            @Value("${app.billing.business-zone:Africa/Ouagadougou}") String businessZone) {
        this.itemRepository = itemRepository;
        this.priceRepository = priceRepository;
        this.links = links;
        this.labTestDefinitionRepository = labTestDefinitionRepository;
        this.treatmentRepository = treatmentRepository;
        this.userRepository = userRepository;
        this.roleValidator = roleValidator;
        this.actingScopeResolver = actingScopeResolver;
        this.auditEventLogService = auditEventLogService;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    // ───────────────────────────── reads ─────────────────────────────

    /** The acting hospital's items (every hospital's in the super-admin's global view), by code. */
    @Transactional(readOnly = true)
    public List<PriceListItemDTO> list(PriceListCategory category, boolean includeInactive) {
        UUID hospitalId = requireReader();
        List<PriceListItem> items = (hospitalId == null
            ? itemRepository.findAllByOrderByCodeAsc()
            : itemRepository.findByHospitalIdOrderByCodeAsc(hospitalId)).stream()
            .filter(item -> category == null || item.getCategory() == category)
            .filter(item -> includeInactive || item.isActive())
            .toList();
        if (items.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<PriceListPrice>> versions = priceRepository
            .findByItemIds(items.stream().map(PriceListItem::getId).toList()).stream()
            .collect(Collectors.groupingBy(p -> p.getItem().getId()));
        Names names = names(items, versions.values().stream().flatMap(List::stream).toList());
        LinkLabels linkLabels = linkLabels(items);
        LocalDate today = today();
        return items.stream()
            .map(item -> toDto(item, versions.getOrDefault(item.getId(), List.of()), names, linkLabels, today, false))
            .toList();
    }

    /** One item with its whole price history (who, when, why). */
    @Transactional(readOnly = true)
    public PriceListItemDTO get(UUID itemId) {
        UUID hospitalId = requireReader();
        PriceListItem item = (hospitalId == null
            ? itemRepository.findById(itemId)
            : itemRepository.findByIdAndHospitalId(itemId, hospitalId))
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_KEY, itemId));
        return detail(item);
    }

    private PriceListItemDTO detail(PriceListItem item) {
        List<PriceListPrice> history = priceRepository.findHistory(item.getId(), item.getHospitalId());
        return toDto(item, history, names(List.of(item), history), linkLabels(List.of(item)), today(), true);
    }

    /** What an item of the acting hospital can be linked to (the "Lier à" picker). */
    @Transactional(readOnly = true)
    public PriceListLinkOptionsDTO linkOptions() {
        UUID hospitalId = requireReader();
        List<PriceListLinkOptionsDTO.Option> labTests = hospitalId == null ? List.of()
            : labTestDefinitionRepository.findVisibleAt(hospitalId).stream()
                .filter(LabTestDefinition::isActive)
                .sorted(Comparator.comparing(d -> Objects.toString(d.getTestCode(), "")))
                .map(d -> PriceListLinkOptionsDTO.Option.builder()
                    .id(d.getId()).code(d.getTestCode()).name(d.getName()).build())
                .toList();
        List<PriceListLinkOptionsDTO.Option> treatments = hospitalId == null ? List.of()
            : treatmentRepository.findByHospital_Id(hospitalId).stream()
                .sorted(Comparator.comparing(t -> Objects.toString(t.getName(), "")))
                .map(t -> PriceListLinkOptionsDTO.Option.builder().id(t.getId()).name(t.getName()).build())
                .toList();
        return PriceListLinkOptionsDTO.builder()
            .systemKeys(Arrays.stream(PriceListSystemKey.values()).map(Enum::name).toList())
            .transfusionProducts(Arrays.stream(BloodProductType.values()).map(Enum::name).toList())
            .imagingModalities(Arrays.stream(ImagingModality.values()).map(Enum::name).toList())
            .labTests(labTests)
            .treatments(treatments)
            .build();
    }

    // ───────────────────────────── writes ─────────────────────────────

    /** A new item with its first price. */
    @Transactional
    public PriceListItemDTO create(PriceListItemRequestDTO request) {
        Maintainer actor = requireMaintainer();
        PriceListItemRequestDTO body = request != null ? request : new PriceListItemRequestDTO();
        String code = body.getCode() == null ? "" : body.getCode().trim().toUpperCase(java.util.Locale.ROOT);
        String label = body.getLabel() == null ? "" : body.getLabel().trim();
        RequiredFields.check()
            .require(!code.isEmpty(), FIELD_CODE, "billing.priceList.codeRequired")
            .require(!label.isEmpty(), FIELD_LABEL, "billing.priceList.labelRequired")
            .require(body.getCategory() != null, FIELD_CATEGORY, "billing.priceList.categoryRequired")
            .require(body.getUnitPrice() != null, PriceVersionRules.FIELD_UNIT_PRICE, "billing.priceList.priceRequired")
            .orThrow();
        if (code.length() > CODE_MAX || !CODE_FORMAT.matcher(code).matches()) {
            throw new FieldBusinessException(FIELD_CODE, "billing.priceList.codeFormat", CODE_MAX);
        }
        requireLabelLength(label);
        PriceVersionRules.requireValidPrice(body.getUnitPrice());
        LocalDate today = today();
        LocalDate from = PriceVersionRules.startOf(body.getEffectiveFrom(), today);
        String reason = optionalReason(body.getChangeReason());
        String linkKey = links.normalise(actor.hospitalId(), body.getLinkType(), body.getLinkKey());
        if (itemRepository.existsByHospitalIdAndCode(actor.hospitalId(), code)) {
            throw new FieldBusinessException(FIELD_CODE, "billing.priceList.codeDuplicate", code);
        }
        requireLinkFree(actor.hospitalId(), body.getLinkType(), linkKey, null);

        PriceListItem item = PriceListItem.builder()
            .hospitalId(actor.hospitalId())
            .code(code)
            .label(label)
            .category(body.getCategory())
            .linkType(linkKey == null ? null : body.getLinkType())
            .linkKey(linkKey)
            .active(true)
            .createdBy(actor.userId())
            .updatedBy(actor.userId())
            .build();
        PriceListItem saved;
        PriceListPrice price;
        try {
            saved = itemRepository.saveAndFlush(item);
            price = priceRepository.saveAndFlush(PriceListPrice.builder()
                .hospitalId(actor.hospitalId())
                .item(saved)
                .unitPrice(wholeFrancs(body.getUnitPrice()))
                .effectiveFrom(from)
                .changeReason(reason)
                .createdBy(actor.userId())
                .build());
        } catch (DataIntegrityViolationException race) {
            // A concurrent create of the same code or link at this hospital won between the checks and the insert.
            throw new FieldBusinessException(FIELD_CODE, "billing.priceList.codeDuplicate", code);
        }
        auditAfterCommit(AuditEventType.PRICE_LIST_ITEM_CREATED, actor,
            "Price list item " + saved.getId() + " created with price version " + price.getId(), saved.getId());
        return detail(saved);
    }

    /** The label, the category and the link; the code and the prices never change here. */
    @Transactional
    public PriceListItemDTO update(UUID itemId, PriceListItemUpdateRequestDTO request) {
        Maintainer actor = requireMaintainer();
        PriceListItem item = lockAtHospital(itemId, actor);
        requireNotWithdrawn(item);
        PriceListItemUpdateRequestDTO body = request != null ? request : new PriceListItemUpdateRequestDTO();
        String label = body.getLabel() == null ? "" : body.getLabel().trim();
        RequiredFields.check()
            .require(!label.isEmpty(), FIELD_LABEL, "billing.priceList.labelRequired")
            .require(body.getCategory() != null, FIELD_CATEGORY, "billing.priceList.categoryRequired")
            .orThrow();
        requireLabelLength(label);
        String linkKey = links.normalise(actor.hospitalId(), body.getLinkType(), body.getLinkKey());
        requireLinkFree(actor.hospitalId(), body.getLinkType(), linkKey, item.getId());
        item.setLabel(label);
        item.setCategory(body.getCategory());
        item.setLinkType(linkKey == null ? null : body.getLinkType());
        item.setLinkKey(linkKey);
        item.setUpdatedBy(actor.userId());
        try {
            itemRepository.saveAndFlush(item);
        } catch (DataIntegrityViolationException race) {
            throw new FieldBusinessException(PriceListLinks.FIELD_LINK_KEY, "billing.priceList.linkTaken",
                linkKey, item.getCode());
        }
        auditAfterCommit(AuditEventType.PRICE_LIST_ITEM_UPDATED, actor,
            "Price list item " + item.getId() + " updated", item.getId());
        return detail(item);
    }

    /**
     * A new price version. Under the item's lock: the version in force is closed on the day before the new one
     * starts, and a planned version the new one overlaps is replaced only when the caller confirms it.
     */
    @Transactional
    public PriceListItemDTO addPrice(UUID itemId, PriceListPriceRequestDTO request) {
        Maintainer actor = requireMaintainer();
        PriceListItem item = lockAtHospital(itemId, actor);
        requireNotWithdrawn(item);
        PriceListPriceRequestDTO body = request != null ? request : new PriceListPriceRequestDTO();
        String reason = body.getChangeReason() == null ? "" : body.getChangeReason().trim();
        RequiredFields.check()
            .require(body.getUnitPrice() != null, PriceVersionRules.FIELD_UNIT_PRICE, "billing.priceList.priceRequired")
            .require(!reason.isEmpty(), FIELD_CHANGE_REASON, "billing.priceList.reasonRequired")
            .orThrow();
        PriceVersionRules.requireValidPrice(body.getUnitPrice());
        requireReasonLength(reason);
        LocalDate today = today();
        LocalDate from = PriceVersionRules.startOf(body.getEffectiveFrom(), today);
        List<PriceListPrice> versions = priceRepository.findHistory(item.getId(), actor.hospitalId());
        PriceVersionRules.Plan plan = PriceVersionRules.plan(versions, today, from, body.isReplaceScheduled());

        // Close first, then insert: the partial unique indexes (one live version per start, one open-ended
        // version per item) hold at every statement.
        for (PriceListPrice replaced : plan.toReplace()) {
            replaced.setEffectiveTo(replaced.getEffectiveFrom().minusDays(1));
        }
        plan.predecessor().ifPresent(previous -> previous.setEffectiveTo(from.minusDays(1)));
        priceRepository.saveAll(versions);
        priceRepository.flush();
        PriceListPrice version = priceRepository.saveAndFlush(PriceListPrice.builder()
            .hospitalId(actor.hospitalId())
            .item(item)
            .unitPrice(wholeFrancs(body.getUnitPrice()))
            .effectiveFrom(from)
            .changeReason(reason)
            .createdBy(actor.userId())
            .build());
        item.setUpdatedBy(actor.userId());
        itemRepository.save(item);
        auditAfterCommit(AuditEventType.PRICE_VERSION_ADDED, actor,
            "Price version " + version.getId() + " added to price list item " + item.getId()
                + ", effective " + from,
            item.getId());
        return detail(item);
    }

    /** The item no longer prices anything new; its history stays, with who deactivated it, when and why. */
    @Transactional
    public PriceListItemDTO deactivate(UUID itemId, PriceListDeactivateRequestDTO request) {
        Maintainer actor = requireMaintainer();
        PriceListItem item = itemRepository.lockByIdAtHospital(itemId, actor.hospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_KEY, itemId));
        if (item.isActive()) {
            String reason = request == null || request.getReason() == null ? "" : request.getReason().trim();
            RequiredFields.check()
                .require(!reason.isEmpty(), FIELD_REASON, "billing.priceList.deactivationReasonRequired")
                .orThrow();
            if (reason.length() > REASON_MAX) {
                throw new FieldBusinessException(FIELD_REASON, "billing.priceList.reasonTooLong", REASON_MAX);
            }
            item.setActive(false);
            item.setDeactivatedAt(java.time.LocalDateTime.now(clock));
            item.setDeactivatedBy(actor.userId());
            item.setDeactivationReason(reason);
            item.setUpdatedBy(actor.userId());
            itemRepository.save(item);
            auditAfterCommit(AuditEventType.PRICE_LIST_ITEM_DEACTIVATED, actor,
                "Price list item " + item.getId() + " deactivated", item.getId());
        }
        return detail(item);
    }

    // ───────────────────────────── access ─────────────────────────────

    /** Who may write, at which hospital. */
    record Maintainer(UUID userId, UUID hospitalId) {
    }

    /**
     * The acting hospital (pinned, never global view) when the caller holds HOSPITAL_ADMIN or ACCOUNTANT there.
     * Checked before any lookup.
     */
    Maintainer requireMaintainer() {
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null || !roleValidator.canMaintainPriceList(userId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.priceList.maintainersOnly"));
        }
        return new Maintainer(userId, hospitalId);
    }

    /**
     * The hospital whose list the caller reads: the acting one, where the caller holds a reading role; null for
     * the verified super-admin's global view (read-only).
     */
    UUID requireReader() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null || actingScopeResolver.isVerifiedSuperAdmin()) {
            return hospitalId;
        }
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null || !roleValidator.canReadPriceList(userId, hospitalId)) {
            throw new AccessDeniedException("Access denied");
        }
        return hospitalId;
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private PriceListItem lockAtHospital(UUID itemId, Maintainer actor) {
        return itemRepository.lockByIdAtHospital(itemId, actor.hospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_KEY, itemId));
    }

    /** A deactivated item is closed: it takes no new price and no edit. */
    private static void requireNotWithdrawn(PriceListItem item) {
        if (!item.isActive()) {
            throw new com.example.hms.exception.BusinessException("billing.priceList.inactive");
        }
    }

    private void requireLinkFree(UUID hospitalId, PriceListLinkType type, String linkKey, UUID self) {
        if (linkKey == null) {
            return;
        }
        Optional<PriceListItem> holder = itemRepository.findActiveByLink(hospitalId, type, linkKey).stream()
            .filter(other -> !other.getId().equals(self))
            .findFirst();
        if (holder.isPresent()) {
            throw new FieldBusinessException(PriceListLinks.FIELD_LINK_KEY, "billing.priceList.linkTaken",
                linkKey, holder.get().getCode());
        }
    }

    private static void requireLabelLength(String label) {
        if (label.length() > LABEL_MAX) {
            throw new FieldBusinessException(FIELD_LABEL, "billing.priceList.labelTooLong", LABEL_MAX);
        }
    }

    private static void requireReasonLength(String reason) {
        if (reason.length() > REASON_MAX) {
            throw new FieldBusinessException(FIELD_CHANGE_REASON, "billing.priceList.reasonTooLong", REASON_MAX);
        }
    }

    private static String optionalReason(String raw) {
        String reason = raw == null ? "" : raw.trim();
        requireReasonLength(reason);
        return reason.isEmpty() ? null : reason;
    }

    private static BigDecimal wholeFrancs(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(businessZone));
    }

    private void auditAfterCommit(AuditEventType type, Maintainer actor, String description, UUID itemId) {
        String itemIdText = String.valueOf(itemId);
        UUID userId = actor.userId();
        UUID hospitalId = actor.hospitalId();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(type)
                    .status(AuditStatus.SUCCESS)
                    .userId(userId)
                    .hospitalId(hospitalId)
                    .eventDescription(description)
                    .entityType(AUDIT_ENTITY)
                    .resourceId(itemIdText)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Price list audit {} not written for item {}: {}", type, itemIdText,
                    ex.getClass().getSimpleName());
            }
        });
    }

    // ───────────────────────────── mapping ─────────────────────────────

    /** Display names of the people on these rows, read in one query. */
    private record Names(Map<UUID, String> byId) {
        String of(UUID id) {
            return id == null ? null : byId.get(id);
        }
    }

    private Names names(Collection<PriceListItem> items, Collection<PriceListPrice> prices) {
        Set<UUID> ids = new HashSet<>();
        items.forEach(i -> {
            ids.add(i.getCreatedBy());
            ids.add(i.getUpdatedBy());
            ids.add(i.getDeactivatedBy());
        });
        prices.forEach(p -> ids.add(p.getCreatedBy()));
        ids.remove(null);
        if (ids.isEmpty()) {
            return new Names(Map.of());
        }
        return new Names(userRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(User::getId, PriceListService::displayName, (a, b) -> a)));
    }

    /** What the lab-test and service links name, read in two queries at most. */
    private record LinkLabels(Map<UUID, String> labTests, Map<UUID, String> treatments) {
    }

    private LinkLabels linkLabels(Collection<PriceListItem> items) {
        Set<UUID> labIds = idsOf(items, PriceListLinkType.LAB_TEST_DEFINITION);
        Set<UUID> treatmentIds = idsOf(items, PriceListLinkType.TREATMENT);
        Map<UUID, String> labTests = labIds.isEmpty() ? Map.of()
            : labTestDefinitionRepository.findAllById(labIds).stream().collect(Collectors.toMap(
                LabTestDefinition::getId,
                d -> d.getTestCode() == null ? Objects.toString(d.getName(), "") : d.getTestCode() + " · " + d.getName(),
                (a, b) -> a));
        Map<UUID, String> treatments = treatmentIds.isEmpty() ? Map.of()
            : treatmentRepository.findAllById(treatmentIds).stream()
                .collect(Collectors.toMap(Treatment::getId, t -> Objects.toString(t.getName(), ""), (a, b) -> a));
        return new LinkLabels(labTests, treatments);
    }

    private static Set<UUID> idsOf(Collection<PriceListItem> items, PriceListLinkType type) {
        Set<UUID> ids = new HashSet<>();
        for (PriceListItem item : items) {
            if (item.getLinkType() == type) {
                parse(item.getLinkKey()).ifPresent(ids::add);
            }
        }
        return ids;
    }

    private static Optional<UUID> parse(String key) {
        try {
            return Optional.of(UUID.fromString(key));
        } catch (IllegalArgumentException | NullPointerException notAnId) {
            return Optional.empty();
        }
    }

    private static String linkLabelOf(PriceListItem item, LinkLabels labels) {
        if (item.getLinkType() == null) {
            return null;
        }
        Function<Map<UUID, String>, String> byId = map -> parse(item.getLinkKey()).map(map::get).orElse(null);
        return switch (item.getLinkType()) {
            case LAB_TEST_DEFINITION -> byId.apply(labels.labTests());
            case TREATMENT -> byId.apply(labels.treatments());
            default -> item.getLinkKey();
        };
    }

    private PriceListItemDTO toDto(PriceListItem item, List<PriceListPrice> versions, Names names,
                                   LinkLabels linkLabels, LocalDate today, boolean withHistory) {
        Map<UUID, PriceListPriceDTO> byId = new HashMap<>();
        for (PriceListPrice version : versions) {
            byId.put(version.getId(), toDto(version, names, today));
        }
        PriceListPriceDTO current = versions.stream().filter(v -> v.coversDay(today)).findFirst()
            .map(v -> byId.get(v.getId())).orElse(null);
        PriceListPriceDTO next = versions.stream()
            .filter(v -> !v.isReplaced() && v.getEffectiveFrom().isAfter(today))
            .min(Comparator.comparing(PriceListPrice::getEffectiveFrom))
            .map(v -> byId.get(v.getId())).orElse(null);
        return PriceListItemDTO.builder()
            .id(item.getId())
            .hospitalId(item.getHospitalId())
            .code(item.getCode())
            .label(item.getLabel())
            .category(item.getCategory())
            .linkType(item.getLinkType())
            .linkKey(item.getLinkKey())
            .linkLabel(linkLabelOf(item, linkLabels))
            .active(item.isActive())
            .deactivatedAt(item.getDeactivatedAt())
            .deactivatedByName(names.of(item.getDeactivatedBy()))
            .deactivationReason(item.getDeactivationReason())
            .currentPrice(current)
            .nextPrice(next)
            .createdAt(item.getCreatedAt())
            .createdByName(names.of(item.getCreatedBy()))
            .updatedAt(item.getUpdatedAt())
            .updatedByName(names.of(item.getUpdatedBy()))
            .history(withHistory ? versions.stream().map(v -> byId.get(v.getId())).toList() : null)
            .build();
    }

    private static PriceListPriceDTO toDto(PriceListPrice version, Names names, LocalDate today) {
        return PriceListPriceDTO.builder()
            .id(version.getId())
            .unitPrice(version.getUnitPrice())
            .effectiveFrom(version.getEffectiveFrom())
            .effectiveTo(version.isReplaced() ? null : version.getEffectiveTo())
            .status(PriceVersionRules.statusOf(version, today))
            .changeReason(version.getChangeReason())
            .createdAt(version.getCreatedAt())
            .createdByName(names.of(version.getCreatedBy()))
            .build();
    }

    private static String displayName(User user) {
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }
}
