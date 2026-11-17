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

        InvoiceItem saved = invoiceItemRepository.save(item);

        // Keep invoice totals in sync
        billingInvoiceService.recomputeAndPersistTotals(invoice.getId());

        return invoiceItemMapper.toInvoiceItemResponseDTO(saved);
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
        return invoiceItemRepository.findByBillingInvoice_Id(invoiceId).stream()
            .map(invoiceItemMapper::toInvoiceItemResponseDTO)
            .toList();
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
        if (!com.example.hms.service.billing.BillingTextRenderer.isShownText(dto.getItemDescription(), item.getTextCode(),
                item.getTextArgs(), item.getItemDescription())) {
            item.setItemDescription(dto.getItemDescription());
            item.setTextCode(null);
            item.setTextArgs(null);
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
     *   <li>Any other line priced by hand is MANUAL, with no reason (an unpriced line priced, as before).</li>
     * </ul>
     * The line's invoice is already known to be a DRAFT of the acting hospital ({@link #writableInvoice}).
     */
    private PriceChange applyPrice(InvoiceItem item, java.math.BigDecimal newPrice, String rawReason,
                                   BillingInvoice invoice) {
        java.math.BigDecimal current = item.getUnitPrice();
        boolean changed = current == null || newPrice.compareTo(current) != 0;
        java.math.BigDecimal tariff = com.example.hms.mapper.InvoiceItemMapper.tariffUnitPriceOf(item);
        if (tariff == null) {
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
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.isEmpty()) {
            throw new FieldBusinessException(FIELD_OVERRIDE_REASON, "billing.item.overrideReasonRequired");
        }
        if (reason.length() > OVERRIDE_REASON_MAX) {
            throw new FieldBusinessException(FIELD_OVERRIDE_REASON, "billing.item.overrideReasonTooLong",
                OVERRIDE_REASON_MAX);
        }
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

    /** What a line edit did to a price-list price. */
    private enum PriceChange { NONE, OVERRIDDEN, RESTORED }

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
     * with a reason, by a billing specialist or a hospital admin of the hospital; every deletion is audited (ids in
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
        if (tariffLine) {
            if (reason.isEmpty()) {
                throw new FieldBusinessException(FIELD_DELETE_REASON, "billing.item.deleteReasonRequired");
            }
            // QA D2101: a refused removal names the removal, not a price change.
            if (!isTariffWriter(invoice)) {
                throw new ClientSafeAccessDeniedException(
                    com.example.hms.utility.MessageUtil.resolve("billing.item.deleteNotAllowed"));
            }
        }
        if (reason.length() > OVERRIDE_REASON_MAX) {
            throw new FieldBusinessException(FIELD_DELETE_REASON, "billing.item.overrideReasonTooLong",
                OVERRIDE_REASON_MAX);
        }
        UUID priceVersion = item.getPriceListPriceId();
        invoice.getInvoiceItems().remove(item);
        invoiceItemRepository.delete(item);
        auditLineAfterCommit(AuditEventType.INVOICE_LINE_DELETED, "Draft line " + id + " of invoice " + invoiceId
            + " deleted" + (priceVersion != null ? " (tariff price version " + priceVersion + ")" : ""),
            id, invoice, reason.isEmpty() ? null : reason);

        // recompute after deletion
        billingInvoiceService.recomputeAndPersistTotals(invoiceId);
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
