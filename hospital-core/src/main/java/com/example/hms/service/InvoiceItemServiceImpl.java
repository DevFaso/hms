package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.InvoiceLinePriceSource;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.InvoiceItemMapper;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.Treatment;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.InvoiceItemRequestDTO;
import com.example.hms.payload.dto.InvoiceItemResponseDTO;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.utility.RoleValidator;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.security.tenant.ActingScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceItemServiceImpl implements InvoiceItemService {
    /** Request properties a refusal names (QA D1352 class, round 30). */
    private static final String FIELD_BILLING_INVOICE_ID = "billingInvoiceId";
    private static final String FIELD_OVERRIDE_REASON = "overrideReason";
    private static final String FIELD_DELETE_REASON = "reason";
    private static final int OVERRIDE_REASON_MAX = 500;

    private static final String INVOICE_ITEM_NOT_FOUND_KEY = "invoiceitem.notfound";

    // Sonar S1192 (Pattern 5 of docs/SonarQubeInstructions.md): the
    // i18n key for "billing invoice not found" appears 4x in this file.
    // Naming follows the existing INVOICE_ITEM_NOT_FOUND_KEY pattern.
    private static final String BILLING_INVOICE_NOT_FOUND_KEY = "billinginvoice.notfound";


    private final InvoiceItemRepository invoiceItemRepository;
    private final BillingInvoiceRepository billingInvoiceRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final TreatmentRepository treatmentRepository;
    private final InvoiceItemMapper invoiceItemMapper;
    private final BillingInvoiceService billingInvoiceService;
    private final MessageSource messageSource;
    private final RoleValidator roleValidator;
    private final ActingScopeResolver actingScopeResolver;
    private final AuditEventLogService auditEventLogService;

    /**
     * QA D2437: a draft's lines carry the estimate of the payer's part. Optional so the hand-built unit tests keep
     * working, as in BillingInvoiceServiceImpl.
     */
    private com.example.hms.service.billing.InvoiceCoverageService coverageService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCoverageService(com.example.hms.service.billing.InvoiceCoverageService coverageService) {
        this.coverageService = coverageService;
    }

    /** PR 10 review (V309, QA D2703): the lines taken off drafts, kept for the day report. Optional like the above. */
    private com.example.hms.repository.InvoiceLineRemovalRepository lineRemovalRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLineRemovalRepository(com.example.hms.repository.InvoiceLineRemovalRepository repository) {
        this.lineRemovalRepository = repository;
    }

    /** QA D3980: the price list a line typed at the till is picked from. Optional like the above (hand-built tests). */
    private com.example.hms.repository.PriceListItemRepository priceListItemRepository;
    private com.example.hms.service.billing.TariffResolver tariffResolver;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPriceList(com.example.hms.repository.PriceListItemRepository priceListItemRepository,
                      com.example.hms.service.billing.TariffResolver tariffResolver) {
        this.priceListItemRepository = priceListItemRepository;
        this.tariffResolver = tariffResolver;
    }

    @Override
    @Transactional
    public InvoiceItemResponseDTO createInvoiceItem(InvoiceItemRequestDTO dto, Locale locale) {
        if (dto.getBillingInvoiceId() == null) {
            throw new FieldBusinessException(FIELD_BILLING_INVOICE_ID, "invoiceitem.invoiceRequired");
        }
        requireWholePrice(dto.getUnitPrice());
        requireLineTotalInRange(dto.getUnitPrice(), dto.getQuantity());
        BillingInvoice invoice = writableInvoice(dto.getBillingInvoiceId());
        UserRoleHospitalAssignment assignment = resolveAssignment(dto.getAssignmentId(), invoice);
        Treatment related = resolveRelatedService(dto.getRelatedServiceId(), invoice);

        InvoiceItem item = invoiceItemMapper.toInvoiceItem(dto, invoice, assignment, related);
        String reason = handAddedLineReason(invoice, dto.getOverrideReason());
        item.setOverrideReason(reason);
        // QA D3980 (r77): a line typed at the till bills the price-list act the cashier picked, or no act at all
        // (never claimed from an insurer: InvoiceCoverageService reads a line with no category as not covered).
        PriceChange linked = dto.getPriceListItemId() == null ? PriceChange.NONE
            : linkToPriceList(item, dto.getPriceListItemId(), dto.getOverrideReason(), invoice);

        InvoiceItem saved = invoiceItemRepository.save(item);
        if (linked == PriceChange.OVERRIDDEN) {
            auditLineAfterCommit(AuditEventType.INVOICE_LINE_PRICE_OVERRIDDEN, "Tariff price version "
                + saved.getPriceListPriceId() + " overridden on draft line " + saved.getId() + " of invoice "
                + invoice.getId(), saved.getId(), invoice, saved.getOverrideReason());
        } else if (reason != null) {
            auditLineAfterCommit(AuditEventType.INVOICE_LINE_PRICED_MANUALLY,
                pricedByHand(saved.getId(), invoice.getId()), saved.getId(), invoice, reason);
        }

        // Keep invoice totals in sync
        billingInvoiceService.recomputeAndPersistTotals(invoice.getId());

        return invoiceItemMapper.toInvoiceItemResponseDTO(saved);
    }

    /**
     * QA D3980 (r77): a line typed at the till that bills an item of the hospital's price list. The line names the
     * item (its act: an item rule of the patient's plan, an exclusion included, applies to it) and the item's
     * category, and is worded with the item's label as the hospital typed it. Priced at the version in force on the
     * invoice's service date it is a TARIFF line; at another price it is an override, as on a posted line (D-2): a
     * reason, and a billing specialist or a hospital admin of the hospital. An item with no price that day keeps the
     * price typed (MANUAL) and still bills its act. Another hospital's item, or a withdrawn one, is refused.
     */
    private PriceChange linkToPriceList(InvoiceItem item, UUID priceListItemId, String rawReason,
                                        BillingInvoice invoice) {
        UUID hospitalId = invoice.getHospital().getId();
        com.example.hms.model.PriceListItem picked = priceListItemRepository == null ? null
            : priceListItemRepository.findActiveByIdForCharge(priceListItemId, hospitalId)
                .filter(found -> hospitalId.equals(found.getHospitalId()))
                .orElse(null);
        if (picked == null) {
            throw new FieldBusinessException(FIELD_PRICE_LIST_ITEM, "billing.item.priceListItemUnknown");
        }
        item.setPriceListItemId(picked.getId());
        item.setCoverageCategory(picked.getCategory());
        String label = picked.getLabel() == null ? "" : picked.getLabel().strip();
        if (!label.isEmpty()) {
            item.setItemDescription(label.length() > 255 ? label.substring(0, 255) : label);
            item.setTextCode(null);
            item.setTextArgs(null);
        }
        item.setItemCategory(picked.getCategory() == com.example.hms.enums.PriceListCategory.LAB_TEST
            ? com.example.hms.enums.ItemCategory.LAB_TEST : com.example.hms.enums.ItemCategory.SERVICE);
        java.time.LocalDate day = serviceDayOf(invoice);
        java.util.Optional<com.example.hms.service.billing.TariffResolver.Tariff> tariff = tariffResolver == null
            ? java.util.Optional.empty() : tariffResolver.resolveItem(hospitalId, picked.getId(), day);
        if (tariff.isEmpty()) {
            item.setPriceSource(InvoiceLinePriceSource.MANUAL);
            return PriceChange.NONE;
        }
        java.math.BigDecimal gridPrice = tariff.get().unitPrice();
        item.setPriceListPriceId(tariff.get().priceId());
        if (item.getUnitPrice() != null && gridPrice != null && item.getUnitPrice().compareTo(gridPrice) == 0) {
            item.setPriceSource(InvoiceLinePriceSource.TARIFF);
            return PriceChange.NONE;
        }
        String reason = requireOverrideReason(rawReason);
        if (!isTariffWriter(invoice)) {
            throw new ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolve("billing.item.overrideNotAllowed"));
        }
        item.setPriceSource(InvoiceLinePriceSource.MANUAL);
        item.setTariffUnitPrice(gridPrice);
        item.setOverrideReason(reason);
        return PriceChange.OVERRIDDEN;
    }

    private static final String FIELD_PRICE_LIST_ITEM = "priceListItemId";

    /**
     * The day a typed line is priced on: the invoice's date (its service date, as the checkout and the coverage read
     * it), else today in the hospital's business zone. One rule for the picker's price and the create's check.
     */
    private java.time.LocalDate serviceDayOf(BillingInvoice invoice) {
        if (invoice.getInvoiceDate() != null) {
            return invoice.getInvoiceDate();
        }
        return tariffResolver != null ? tariffResolver.today() : java.time.LocalDate.now();
    }

    @Override
    @Transactional(readOnly = true)
    public com.example.hms.payload.dto.billing.InvoiceLineGridPriceDTO gridPrice(UUID invoiceId,
                                                                               UUID priceListItemId) {
        if (priceListItemId == null) {
            throw new FieldBusinessException(FIELD_PRICE_LIST_ITEM, "billing.item.priceListItemUnknown");
        }
        BillingInvoice invoice = writableInvoice(invoiceId);
        UUID hospitalId = invoice.getHospital().getId();
        boolean offered = priceListItemRepository != null && priceListItemRepository
            .findByIdAndHospitalId(priceListItemId, hospitalId)
            .filter(found -> found.isActive() && hospitalId.equals(found.getHospitalId()))
            .isPresent();
        if (!offered) {
            throw new FieldBusinessException(FIELD_PRICE_LIST_ITEM, "billing.item.priceListItemUnknown");
        }
        java.time.LocalDate day = serviceDayOf(invoice);
        java.math.BigDecimal price = tariffResolver == null ? null
            : tariffResolver.peekItem(hospitalId, priceListItemId, day)
                .map(com.example.hms.service.billing.TariffResolver.Tariff::unitPrice).orElse(null);
        return new com.example.hms.payload.dto.billing.InvoiceLineGridPriceDTO(priceListItemId, day, price);
    }

    /**
     * PR 10 review (QA D2703): the checkout prices a visit's charges; a line typed beside them on the visit's
     * invoice is priced by hand, and an unpriced charge removed and typed again at any price left no trace. Such a
     * line needs {@code overrideReason}: kept on the line, audited, listed in the day report. An invoice made by
     * hand with no visit is hand lines throughout: a reason there is kept when given, never required.
     */
    private static String handAddedLineReason(BillingInvoice invoice, String rawReason) {
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.isEmpty()) {
            if (invoice.getEncounter() != null) {
                throw new FieldBusinessException(FIELD_OVERRIDE_REASON, "billing.item.addedLineReasonRequired");
            }
            return null;
        }
        return requireOverrideReason(reason);
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceItemResponseDTO getInvoiceItemById(UUID id, Locale locale) {
        InvoiceItem item = invoiceItemRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(INVOICE_ITEM_NOT_FOUND_KEY, id));
        // ── Tenant isolation ──
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && item.getBillingInvoice() != null
                && item.getBillingInvoice().getHospital() != null
                && !activeHospitalId.equals(item.getBillingInvoice().getHospital().getId())) {
            throw new ResourceNotFoundException(INVOICE_ITEM_NOT_FOUND_KEY, id);
        }
        return invoiceItemMapper.toInvoiceItemResponseDTO(item);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceItemResponseDTO> getItemsByInvoiceId(UUID invoiceId, Locale locale) {
        // ── Tenant isolation: verify the parent invoice belongs to caller's hospital ──
        BillingInvoice invoice = billingInvoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND_KEY, invoiceId));
        UUID activeHospitalId = roleValidator.requireActiveHospitalId();
        if (activeHospitalId != null && invoice.getHospital() != null
                && !activeHospitalId.equals(invoice.getHospital().getId())) {
            throw new ResourceNotFoundException(BILLING_INVOICE_NOT_FOUND_KEY, invoiceId);
        }
        List<InvoiceItemResponseDTO> lines = invoiceItemRepository.findByBillingInvoice_Id(invoiceId).stream()
            .map(invoiceItemMapper::toInvoiceItemResponseDTO)
            .toList();
        // QA D2437: a draft shows what the patient's coverage would pay if it were issued now, marked an estimate.
        if (coverageService != null) {
            coverageService.previewForDraft(invoice).ifPresent(preview -> lines.forEach(line ->
                InvoiceItemMapper.withShares(line, preview.lines().stream()
                    .filter(share -> share.id().equals(line.getId()))
                    .map(com.example.hms.service.billing.CoverageCalculator.LineShare::share)
                    .findFirst().orElse(java.math.BigDecimal.ZERO), true)));
        }
        return lines;
    }

    @Override
    @Transactional
    public InvoiceItemResponseDTO updateInvoiceItem(UUID id, InvoiceItemRequestDTO dto, Locale locale) {
        requireWholePrice(dto.getUnitPrice());
        requireLineTotalInRange(dto.getUnitPrice(), dto.getQuantity());
        InvoiceItem item = invoiceItemRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(INVOICE_ITEM_NOT_FOUND_KEY, id));
        // A line stays on its invoice: moving it would leave the old total stale.
        UUID invoiceId = item.getBillingInvoice().getId();
        if (dto.getBillingInvoiceId() != null && !dto.getBillingInvoiceId().equals(invoiceId)) {
            throw new ResourceNotFoundException(INVOICE_ITEM_NOT_FOUND_KEY, id);
        }
        BillingInvoice invoice = writableInvoice(invoiceId, INVOICE_ITEM_NOT_FOUND_KEY, id);
        UserRoleHospitalAssignment assignment = dto.getAssignmentId() == null && item.getAssignment() != null
            ? item.getAssignment()
            : resolveAssignment(dto.getAssignmentId(), invoice);
        // QA D1088: a line priced in place keeps the service it was charged
        // for, which is the checkout's link, as it keeps its owner, unless
        // the body names another one.
        Treatment related = dto.getRelatedServiceId() == null && item.getRelatedService() != null
            ? item.getRelatedService()
            : resolveRelatedService(dto.getRelatedServiceId(), invoice);

        // Billing-money plan PR 3: the price first, so a refused override changes nothing on the line.
        PriceChange priceChange = applyPrice(item, dto.getUnitPrice(), dto.getOverrideReason(), invoice);

        // apply updates
        item.setBillingInvoice(invoice);
        // QA D1901: pricing a line in place echoes its description in the
        // reader's language; an echo keeps the server's code, anything else
        // is the person's own text, shown as typed.
        boolean rewritten = !com.example.hms.service.billing.BillingTextRenderer.isShownText(dto.getItemDescription(),
            item.getTextCode(), item.getTextArgs(), item.getItemDescription());
        if (rewritten) {
            item.setItemDescription(dto.getItemDescription());
            item.setTextCode(null);
            item.setTextArgs(null);
        }
        // QA D3603 / review of #1301 (L2): the category a plan rule sees is what the line was posted for, kept through
        // a price edit (the till echoes the label and category). A line rewritten as something else - its label or
        // its item category changed - is the cashier's own line: no posted category. QA D3980: like any typed line it
        // is then covered only through its price-list item (the act it bills), and with none it is not claimed.
        if (rewritten || dto.getItemCategory() != item.getItemCategory()) {
            item.setCoverageCategory(null);
        }
        item.setQuantity(dto.getQuantity());
        item.setItemCategory(dto.getItemCategory());
        item.setAssignment(assignment);
        item.setRelatedService(related);
        // totalPrice recalculated by @PreUpdate

        InvoiceItem saved = invoiceItemRepository.save(item);
        if (priceChange == PriceChange.OVERRIDDEN) {
            auditLineAfterCommit(AuditEventType.INVOICE_LINE_PRICE_OVERRIDDEN, "Tariff price version "
                + saved.getPriceListPriceId() + " overridden on draft line " + saved.getId() + " of invoice "
                + invoice.getId(), saved.getId(), invoice, null);
        } else if (priceChange == PriceChange.PRICED_BY_HAND) {
            // QA D2703: an unpriced line priced (or a line set to 0 F) by hand: ids in the description, reason in details.
            auditLineAfterCommit(AuditEventType.INVOICE_LINE_PRICED_MANUALLY,
                pricedByHand(saved.getId(), invoice.getId()), saved.getId(), invoice, saved.getOverrideReason());
        } else if (priceChange == PriceChange.RESTORED) {
            // Review #1197 L2: setting an overridden line back to the price list is audited too.
            auditLineAfterCommit(AuditEventType.INVOICE_LINE_PRICE_RESTORED, "Tariff price version "
                + saved.getPriceListPriceId() + " restored on draft line " + saved.getId() + " of invoice "
                + invoice.getId(), saved.getId(), invoice, null);
        }

        // recompute invoice total
        billingInvoiceService.recomputeAndPersistTotals(invoice.getId());

        return invoiceItemMapper.toInvoiceItemResponseDTO(saved);
    }

    /**
     * Billing-money plan PR 3 (owner decision D-2): the line's new unit price, and where it now comes from.
     *
     * <ul>
     *   <li>A line priced from the price list (TARIFF, or already overridden) changes price only with
     *       {@code overrideReason}, and only by a billing specialist or a hospital admin of the invoice's hospital
     *       (never the super-admin alone). It becomes MANUAL, keeps the tariff's price in {@code tariffUnitPrice}
     *       and the reason, and an audit row is written (returns true).</li>
     *   <li>Set back to the tariff's price, it is a TARIFF line again, with no reason.</li>
     *   <li>Any other line priced by hand is MANUAL. QA D2703: pricing a line that stood at 0 F (an unpriced line),
     *       or setting one to 0 F, needs {@code overrideReason}; the reason is kept on the line, an audit row is
     *       written and the day report lists it. Any other change of a hand-priced line needs no reason.</li>
     * </ul>
     * The line's invoice is already known to be a DRAFT of the acting hospital ({@link #writableInvoice}).
     */
    private PriceChange applyPrice(InvoiceItem item, java.math.BigDecimal newPrice, String rawReason,
                                   BillingInvoice invoice) {
        java.math.BigDecimal current = item.getUnitPrice();
        boolean changed = current == null || newPrice.compareTo(current) != 0;
        java.math.BigDecimal tariff = com.example.hms.mapper.InvoiceItemMapper.tariffUnitPriceOf(item);
        if (tariff == null) {
            boolean fromZero = current == null || current.signum() == 0;
            if (changed && (fromZero || newPrice.signum() == 0)) {
                item.setOverrideReason(requireOverrideReason(rawReason));
                item.setUnitPrice(newPrice);
                item.setPriceSource(InvoiceLinePriceSource.MANUAL);
                return PriceChange.PRICED_BY_HAND;
            }
            item.setUnitPrice(newPrice);
            if (changed) {
                item.setPriceSource(InvoiceLinePriceSource.MANUAL);
            }
            return PriceChange.NONE;
        }
        if (newPrice.compareTo(tariff) == 0) {
            // Back at (or still at) the price list's price: a tariff line, nothing overridden.
            boolean wasOverridden = item.getTariffUnitPrice() != null;
            item.setUnitPrice(newPrice);
            item.setPriceSource(InvoiceLinePriceSource.TARIFF);
            item.setTariffUnitPrice(null);
            item.setOverrideReason(null);
            return wasOverridden ? PriceChange.RESTORED : PriceChange.NONE;
        }
        if (!changed) {
            // Overridden before at this same price (the quantity or the label changed): the reason stands.
            return PriceChange.NONE;
        }
        String reason = requireOverrideReason(rawReason);
        if (!isTariffWriter(invoice)) {
            throw new ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolve("billing.item.overrideNotAllowed"));
        }
        item.setUnitPrice(newPrice);
        item.setPriceSource(InvoiceLinePriceSource.MANUAL);
        item.setTariffUnitPrice(tariff);
        item.setOverrideReason(reason);
        return PriceChange.OVERRIDDEN;
    }

    /** The reason a price is set by hand: required, at most 500 characters, never a card number. */
    private static String requireOverrideReason(String rawReason) {
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.isEmpty()) {
            throw new FieldBusinessException(FIELD_OVERRIDE_REASON, "billing.item.overrideReasonRequired");
        }
        if (reason.length() > OVERRIDE_REASON_MAX) {
            throw new FieldBusinessException(FIELD_OVERRIDE_REASON, "billing.item.overrideReasonTooLong",
                OVERRIDE_REASON_MAX);
        }
        com.example.hms.utility.CardNumbers.requireNone(reason, FIELD_OVERRIDE_REASON);
        return reason;
    }

    /** The audit description of a line priced by hand: ids only (the portal's audit pattern reads it). */
    private static String pricedByHand(UUID lineId, UUID invoiceId) {
        return "Draft line " + lineId + " of invoice " + invoiceId + " priced by hand";
    }

    /** What a line edit did to a price-list price (or, QA D2703, to a line with none). */
    private enum PriceChange { NONE, OVERRIDDEN, RESTORED, PRICED_BY_HAND }

    /**
     * D-1/D-2: a billing specialist or a hospital admin of the invoice's hospital, never the super-admin alone.
     * QA D2101: each caller refuses with the message that names its own action (change a price, remove a line).
     */
    private boolean isTariffWriter(BillingInvoice invoice) {
        UUID userId = roleValidator.getCurrentUserId();
        return userId != null && roleValidator.canOverrideTariff(userId, invoice.getHospital().getId());
    }

    /**
     * One audit row after commit. The description carries ids only (no price, no patient); a reason a person typed
     * goes in the row's details, as the national-id override does, never in the description.
     */
    private void auditLineAfterCommit(AuditEventType type, String description, UUID line, BillingInvoice invoice,
                                      String reason) {
        String lineId = String.valueOf(line);
        java.util.Map<String, String> details = reason == null ? null : java.util.Map.of("reason", reason);
        UUID userId = roleValidator.getCurrentUserId();
        UUID hospitalId = invoice.getHospital().getId();
        com.example.hms.utility.TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(com.example.hms.payload.dto.AuditEventRequestDTO.builder()
                    .eventType(type)
                    .status(com.example.hms.enums.AuditStatus.SUCCESS)
                    .userId(userId)
                    .hospitalId(hospitalId)
                    .eventDescription(description)
                    .entityType("INVOICE_ITEM")
                    .resourceId(lineId)
                    .details(details)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Invoice line audit {} not written for line {}: {}", type, lineId,
                    ex.getClass().getSimpleName());
            }
        });
    }

    @Override
    @Transactional
    public void deleteInvoiceItem(UUID id, Locale locale) {
        deleteInvoiceItem(id, null, locale);
    }

    /**
     * Review #1197 M2 (D-2): taking a line priced from the price list off a draft would otherwise let a cashier
     * re-add it lower with no reason, no role check and no trace. A tariff line (overridden or not) is deleted only
     * by a billing specialist or a hospital admin of the hospital.
     *
     * <p>PR 10 review (QA D2703): the same hole stood for a line with no price-list price (an unpriced charge
     * removed, then typed again at any price). EVERY line is now removed only with a reason; the removal is kept
     * ({@code invoice_line_removals}: the line, its price, who, when, why) for the day report, and audited (ids in
     * the description, the reason in the details).
     */
    @Override
    @Transactional
    public void deleteInvoiceItem(UUID id, String rawReason, Locale locale) {
        InvoiceItem item = invoiceItemRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(INVOICE_ITEM_NOT_FOUND_KEY, id));
        UUID invoiceId = item.getBillingInvoice().getId();
        BillingInvoice invoice = writableInvoice(invoiceId, INVOICE_ITEM_NOT_FOUND_KEY, id);
        boolean tariffLine = com.example.hms.mapper.InvoiceItemMapper.tariffUnitPriceOf(item) != null;
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.isEmpty()) {
            throw new FieldBusinessException(FIELD_DELETE_REASON,
                tariffLine ? "billing.item.deleteReasonRequired" : "billing.item.removeReasonRequired");
        }
        // QA D2101: a refused removal names the removal, not a price change.
        if (tariffLine && !isTariffWriter(invoice)) {
            throw new ClientSafeAccessDeniedException(
                com.example.hms.utility.MessageUtil.resolve("billing.item.deleteNotAllowed"));
        }
        if (reason.length() > OVERRIDE_REASON_MAX) {
            throw new FieldBusinessException(FIELD_DELETE_REASON, "billing.item.overrideReasonTooLong",
                OVERRIDE_REASON_MAX);
        }
        com.example.hms.utility.CardNumbers.requireNone(reason, FIELD_DELETE_REASON);
        UUID priceVersion = item.getPriceListPriceId();
        recordRemoval(item, invoice, reason);
        invoice.getInvoiceItems().remove(item);
        invoiceItemRepository.delete(item);
        auditLineAfterCommit(AuditEventType.INVOICE_LINE_DELETED, "Draft line " + id + " of invoice " + invoiceId
            + " deleted" + (priceVersion != null ? " (tariff price version " + priceVersion + ")" : ""),
            id, invoice, reason);

        // recompute after deletion
        billingInvoiceService.recomputeAndPersistTotals(invoiceId);
    }

    /** PR 10 review (QA D2703): what the removed line was, for the day report. */
    private void recordRemoval(InvoiceItem item, BillingInvoice invoice, String reason) {
        UUID userId = roleValidator.getCurrentUserId();
        if (lineRemovalRepository == null || userId == null) {
            return;
        }
        lineRemovalRepository.save(com.example.hms.model.InvoiceLineRemoval.builder()
            .hospitalId(invoice.getHospital().getId())
            .invoiceId(invoice.getId())
            .invoiceNumber(invoice.getInvoiceNumber())
            .itemDescription(item.getItemDescription())
            .textCode(item.getTextCode())
            .textArgs(item.getTextArgs())
            .quantity(item.getQuantity())
            .unitPrice(item.getUnitPrice() != null ? item.getUnitPrice() : java.math.BigDecimal.ZERO)
            .tariffUnitPrice(com.example.hms.mapper.InvoiceItemMapper.tariffUnitPriceOf(item))
            .reason(reason)
            .removedBy(userId)
            .removedAt(java.time.LocalDateTime.now(java.time.ZoneId.systemDefault()))
            .build());
    }

    private BillingInvoice writableInvoice(UUID invoiceId) {
        return writableInvoice(invoiceId, BILLING_INVOICE_NOT_FOUND_KEY, invoiceId);
    }

    /**
     * The invoice a line write targets, at the caller's acting hospital: an
     * invoice of another hospital is answered as not found (no probe), and a
     * cancelled or fully paid invoice takes no line changes.
     */
    private BillingInvoice writableInvoice(UUID invoiceId, String missKey, Object missArg) {
        UUID hospitalId = actingScopeResolver.requirePinned();
        BillingInvoice invoice = billingInvoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(missKey, missArg));
        if (invoice.getHospital() == null || !hospitalId.equals(invoice.getHospital().getId())) {
            throw new ResourceNotFoundException(missKey, missArg);
        }
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_BILLING_INVOICE_ID, "billing.invoice.cancelledLocked");
        }
        // QA D147: a settled invoice is a closed record (its receipt was
        // issued against these lines). Reverse the payment to amend it.
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_BILLING_INVOICE_ID, "billing.invoice.paidLinesLocked");
        }
        // QA D656: an issued invoice is a document the patient may hold; a
        // line added to it (2000 on a SENT invoice) changed what they were
        // billed. It is cancelled (or credited) and re-issued instead.
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new com.example.hms.exception.FieldBusinessException(FIELD_BILLING_INVOICE_ID, "billing.invoice.issuedLinesLocked");
        }
        return invoice;
    }

    /**
     * The staff member the line is attributed to: the one named, who must hold
     * an assignment at the invoice's hospital, or else the caller themself.
     */
    private UserRoleHospitalAssignment resolveAssignment(UUID assignmentId, BillingInvoice invoice) {
        UUID hospitalId = invoice.getHospital().getId();
        if (assignmentId == null) {
            UUID userId = roleValidator.getCurrentUserId();
            if (userId == null) {
                throw new BusinessException("invoiceitem.noAssignment");
            }
            // QA D1632 class: the caller's assignment of the role the request acts under, not any active one.
            return com.example.hms.security.audit.ActingAssignments.resolve(assignmentRepository, userId, hospitalId,
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication())
                .orElseThrow(() -> new BusinessException("invoiceitem.noAssignment"));
        }
        UserRoleHospitalAssignment assignment = assignmentRepository.findById(assignmentId)
            .orElseThrow(() -> new ResourceNotFoundException("assignment.notfound"));
        if (assignment.getHospital() == null || !hospitalId.equals(assignment.getHospital().getId())) {
            throw new ResourceNotFoundException("assignment.notfound");
        }
        return assignment;
    }

    private Treatment resolveRelatedService(UUID relatedServiceId, BillingInvoice invoice) {
        if (relatedServiceId == null) {
            return null;
        }
        Treatment related = treatmentRepository.findById(relatedServiceId)
            .orElseThrow(() -> new ResourceNotFoundException("treatment.notfound", relatedServiceId));
        if (related.getHospital() != null && !invoice.getHospital().getId().equals(related.getHospital().getId())) {
            throw new ResourceNotFoundException("treatment.notfound", relatedServiceId);
        }
        return related;
    }

    /**
     * QA D307: the franc CFA has no minor unit; a payment is refused with
     * decimals (D184), so a line priced with them could never be settled.
     */
    private static void requireWholePrice(java.math.BigDecimal price) {
        if (price != null && price.stripTrailingZeros().scale() > 0) {
            throw new com.example.hms.exception.FieldBusinessException("unitPrice", "billing.payment.wholeAmountOnly");
        }
    }

    /** The largest line total the column holds (numeric(12,2)). */
    private static final java.math.BigDecimal MAX_LINE_TOTAL = new java.math.BigDecimal("9999999999.99");

    /**
     * QA D633: a line whose total does not fit the column was refused by the
     * entity's own validation, in English ("numeric value out of bounds").
     * Checked here first, with the caller's language.
     */
    private static void requireLineTotalInRange(java.math.BigDecimal unitPrice, Integer quantity) {
        if (unitPrice != null && quantity != null
                && unitPrice.multiply(java.math.BigDecimal.valueOf(quantity.longValue())).compareTo(MAX_LINE_TOTAL) > 0) {
            throw new com.example.hms.exception.FieldBusinessException("unitPrice", "billing.item.totalOutOfRange");
        }
    }
}
