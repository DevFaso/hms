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
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.billing.PriceListDeactivateRequestDTO;
import com.example.hms.payload.dto.billing.PriceListItemDTO;
import com.example.hms.payload.dto.billing.PriceListItemRequestDTO;
import com.example.hms.payload.dto.billing.PriceListItemUpdateRequestDTO;
import com.example.hms.payload.dto.billing.PriceListLinkOptionsDTO;
import com.example.hms.payload.dto.billing.PriceListPriceDTO;
import com.example.hms.payload.dto.billing.PriceListPriceRequestDTO;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.PriceListPriceRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.CsvCells;
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
import java.util.ArrayList;
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
    private final InvoiceItemRepository invoiceItemRepository;
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
                            InvoiceItemRepository invoiceItemRepository,
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
        this.invoiceItemRepository = invoiceItemRepository;
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
        // PR 3 (deferred from the #1179 review): the global view lists every hospital's items, so the versions are
        // read IN_CHUNK ids at a time, never in one unbounded IN list.
        Map<UUID, List<PriceListPrice>> versions = new HashMap<>();
        for (List<UUID> chunk : chunksOf(items.stream().map(PriceListItem::getId).toList())) {
            for (PriceListPrice price : priceRepository.findByItemIds(chunk)) {
                versions.computeIfAbsent(price.getItem().getId(), id -> new ArrayList<>()).add(price);
            }
        }
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
        PriceListPrice price = insertItem(actor, new NewItem(code, label, body.getCategory(),
            linkKey == null ? null : body.getLinkType(), linkKey), body.getUnitPrice(), from, reason, null);
        PriceListItem saved = price.getItem();
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
            // The transaction is aborted: the item that took the link cannot be read back.
            throw new FieldBusinessException(PriceListLinks.FIELD_LINK_KEY, "billing.priceList.linkTakenConcurrently",
                linkKey);
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
        PriceVersionRules.Plan plan = planVersion(versions, today, from, body.isReplaceScheduled());
        PriceListPrice version = insertVersion(item, actor, versions, plan, body.getUnitPrice(), from, reason, null);
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

    // ─────────────────── writes shared with the CSV import (PR 4) ───────────────────

    /** A new item as checked by the caller: code upper case, link normalised (both null for no link). */
    public record NewItem(String code, String label, PriceListCategory category, PriceListLinkType linkType, String linkKey) {
    }

    /**
     * Inserts a new item and its first version (the screen's create and the CSV import). The caller has checked
     * the fields, that the code is free and that the link is free; a concurrent insert of the same code or link is
     * still refused here, naming it. {@code importId} is the CSV import that writes it, null on the screen.
     */
    public PriceListPrice insertItem(Maintainer actor, NewItem fields, BigDecimal unitPrice, LocalDate from, String reason,
                              UUID importId) {
        PriceListItem item = newItem(actor, fields);
        try {
            PriceListItem saved = itemRepository.saveAndFlush(item);
            return priceRepository.saveAndFlush(firstVersion(actor, saved, unitPrice, from, reason, importId));
        } catch (DataIntegrityViolationException race) {
            // A concurrent create of the same code or link at this hospital won between the checks and the insert.
            if (isActiveLinkCollision(race)) {
                throw new FieldBusinessException(PriceListLinks.FIELD_LINK_KEY, "billing.priceList.linkTakenConcurrently",
                    fields.linkKey());
            }
            throw new FieldBusinessException(FIELD_CODE, "billing.priceList.codeDuplicate", fields.code());
        }
    }

    /**
     * What a new version starting {@code from} does to the item's {@code versions} (read under the item's lock when
     * it is about to be written): refuses a started, invoiced or unconfirmed same-day version, naming the field.
     */
    public PriceVersionRules.Plan planVersion(List<PriceListPrice> versions, LocalDate today, LocalDate from,
                                       boolean replaceScheduled) {
        // PR 3: a version an invoice line copied is never replaced (read under the item's lock, which a checkout's
        // share lock on the same item serialises with).
        return PriceVersionRules.plan(versions, today, from, replaceScheduled,
            version -> enteredOn(version, today),
            version -> invoiceItemRepository.existsByPriceListPriceIdIn(List.of(version.getId())));
    }

    // ─────────────── the CSV import's batched writes (PR 4 review: one flush per batch) ───────────────

    /**
     * Closes what a planned version closes, on the managed versions, without flushing: the import closes every
     * version of a file first, flushes once, then stages the new ones ({@link #stageVersion}).
     */
    public void closeForNewVersion(PriceVersionRules.Plan plan, LocalDate from) {
        for (PriceListPrice replaced : plan.toReplace()) {
            replaced.setEffectiveTo(replaced.getEffectiveFrom().minusDays(1));
        }
        plan.predecessor().ifPresent(previous -> previous.setEffectiveTo(from.minusDays(1)));
    }

    /** A new item and its first version, persisted without a flush (the import flushes once per batch). */
    public PriceListPrice stageItem(Maintainer actor, NewItem fields, BigDecimal unitPrice, LocalDate from,
                                    String reason, UUID importId) {
        PriceListItem item = itemRepository.save(newItem(actor, fields));
        return priceRepository.save(firstVersion(actor, item, unitPrice, from, reason, importId));
    }

    /** A planned version of a locked item whose closes are already flushed, persisted without a flush. */
    @SuppressWarnings("java:S107") // the plan and every value of the version it writes; a holder would only hide them
    public PriceListPrice stageVersion(PriceListItem item, Maintainer actor, PriceVersionRules.Plan plan,
                                       BigDecimal unitPrice, LocalDate from, String reason, UUID importId) {
        item.setUpdatedBy(actor.userId());
        return priceRepository.save(nextVersion(item, actor, plan, unitPrice, from, reason, importId));
    }

    private static PriceListItem newItem(Maintainer actor, NewItem fields) {
        return PriceListItem.builder()
            .hospitalId(actor.hospitalId())
            .code(fields.code())
            .label(fields.label())
            .category(fields.category())
            .linkType(fields.linkType())
            .linkKey(fields.linkKey())
            .active(true)
            .createdBy(actor.userId())
            .updatedBy(actor.userId())
            .build();
    }

    private static PriceListPrice firstVersion(Maintainer actor, PriceListItem item, BigDecimal unitPrice,
                                               LocalDate from, String reason, UUID importId) {
        return PriceListPrice.builder()
            .hospitalId(actor.hospitalId())
            .item(item)
            .unitPrice(wholeFrancs(unitPrice))
            .effectiveFrom(from)
            .changeReason(reason)
            .createdBy(actor.userId())
            .importId(importId)
            .build();
    }

    @SuppressWarnings("java:S107") // every value of the version it builds
    private static PriceListPrice nextVersion(PriceListItem item, Maintainer actor, PriceVersionRules.Plan plan,
                                              BigDecimal unitPrice, LocalDate from, String reason, UUID importId) {
        return PriceListPrice.builder()
            .hospitalId(actor.hospitalId())
            .item(item)
            .unitPrice(wholeFrancs(unitPrice))
            .effectiveFrom(from)
            .effectiveTo(plan.endsOn())
            .changeReason(reason)
            .createdBy(actor.userId())
            .importId(importId)
            .build();
    }

    /**
     * Writes a planned version of a locked item (the screen's "Nouveau prix" and the CSV import): closes what the
     * plan closes, then inserts the version. {@code importId} is the CSV import that writes it, null on the screen.
     */
    @SuppressWarnings("java:S107") // the plan and every value of the version it writes; a holder would only hide them
    public PriceListPrice insertVersion(PriceListItem item, Maintainer actor, List<PriceListPrice> versions,
                                 PriceVersionRules.Plan plan, BigDecimal unitPrice, LocalDate from, String reason,
                                 UUID importId) {
        // Close first, then insert: the partial unique indexes (one live version per start, one open-ended
        // version per item) hold at every statement.
        closeForNewVersion(plan, from);
        priceRepository.saveAll(versions);
        priceRepository.flush();
        PriceListPrice version = priceRepository.saveAndFlush(
            nextVersion(item, actor, plan, unitPrice, from, reason, importId));
        item.setUpdatedBy(actor.userId());
        itemRepository.save(item);
        return version;
    }

    // ───────────────────────────── access ─────────────────────────────

    /** Who may write, at which hospital. */
    public record Maintainer(UUID userId, UUID hospitalId) {
    }

    /**
     * The acting hospital (pinned, never global view) when the caller holds HOSPITAL_ADMIN or ACCOUNTANT there.
     * Checked before any lookup.
     */
    public Maintainer requireMaintainer() {
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
    public UUID requireReader() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        boolean verifiedSuperAdmin = actingScopeResolver.isVerifiedSuperAdmin();
        if (hospitalId == null) {
            // Global view (every hospital) is the verified super-admin's alone; anything else that resolves no
            // hospital is refused, never read across hospitals.
            if (!verifiedSuperAdmin) {
                throw new AccessDeniedException("Access denied");
            }
            return null;
        }
        if (verifiedSuperAdmin) {
            return hospitalId;
        }
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null || !roleValidator.canReadPriceList(userId, hospitalId)) {
            throw new AccessDeniedException("Access denied");
        }
        return hospitalId;
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** The most ids one IN list carries (well under the driver's bind-parameter limit). */
    static final int IN_CHUNK = 500;

    /** {@code ids} cut into lists of at most {@link #IN_CHUNK}, in order. */
    static List<List<UUID>> chunksOf(List<UUID> ids) {
        List<List<UUID>> chunks = new ArrayList<>();
        for (int start = 0; start < ids.size(); start += IN_CHUNK) {
            chunks.add(ids.subList(start, Math.min(ids.size(), start + IN_CHUNK)));
        }
        return chunks;
    }

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

    /** The one-active-item-per-link index (V302), as opposed to the code's unique constraint. */
    static boolean isActiveLinkCollision(DataIntegrityViolationException race) {
        Throwable cause = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(race);
        String message = cause.getMessage();
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("uq_price_list_item_active_link");
    }

    private static void requireLabelLength(String label) {
        if (label.length() > LABEL_MAX) {
            throw new FieldBusinessException(FIELD_LABEL, "billing.priceList.labelTooLong", LABEL_MAX);
        }
        requireNotFormula(label);
    }

    /**
     * PR 4 review: the label is exported to a spreadsheet, so it never starts like a formula (= + - @, a tab, a
     * carriage return): the same rule the CSV import applies to every cell ({@link CsvCells#isFormula}).
     */
    static void requireNotFormula(String label) {
        if (CsvCells.isFormula(label)) {
            throw new FieldBusinessException(FIELD_LABEL, "billing.priceList.labelFormula");
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

    /**
     * Owner/coordinator decision 2026-11-16 (provisional): a version entered today and in force since today is a
     * same-day correction, not history already applied - unless an invoice line already copied it
     * (invoice_items.price_list_price_id, V303): then it has priced a bill and is refused (PR 3).
     */
    private boolean enteredOn(PriceListPrice version, LocalDate today) {
        return version.getCreatedAt() != null
            && version.getCreatedAt().atZone(ZoneId.systemDefault()).withZoneSameInstant(businessZone).toLocalDate()
                .equals(today);
    }

    public LocalDate today() {
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
        Map<UUID, String> byId = new HashMap<>();
        for (List<UUID> chunk : chunksOf(new ArrayList<>(ids))) {
            userRepository.findAllById(chunk).forEach(user -> byId.putIfAbsent(user.getId(), displayName(user)));
        }
        return new Names(byId);
    }

    /** What the lab-test and service links name, read in two queries at most. */
    private record LinkLabels(Map<UUID, String> labTests, Map<UUID, String> treatments) {
    }

    private LinkLabels linkLabels(Collection<PriceListItem> items) {
        Set<UUID> labIds = idsOf(items, PriceListLinkType.LAB_TEST_DEFINITION);
        Set<UUID> treatmentIds = idsOf(items, PriceListLinkType.TREATMENT);
        // Every IN list in chunks: the global view links every hospital's tests and services (#1179 review).
        Map<UUID, String> labTests = new HashMap<>();
        for (List<UUID> chunk : chunksOf(new ArrayList<>(labIds))) {
            labTestDefinitionRepository.findAllById(chunk).forEach(d -> labTests.putIfAbsent(d.getId(),
                d.getTestCode() == null ? Objects.toString(d.getName(), "") : d.getTestCode() + " · " + d.getName()));
        }
        Map<UUID, String> treatments = new HashMap<>();
        for (List<UUID> chunk : chunksOf(new ArrayList<>(treatmentIds))) {
            treatmentRepository.findAllById(chunk)
                .forEach(t -> treatments.putIfAbsent(t.getId(), Objects.toString(t.getName(), "")));
        }
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
