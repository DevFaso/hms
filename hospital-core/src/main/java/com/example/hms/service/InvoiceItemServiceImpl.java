package com.example.hms.service;

import com.example.hms.exception.BusinessException;
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

@Service
@RequiredArgsConstructor
public class InvoiceItemServiceImpl implements InvoiceItemService {
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

    @Override
    @Transactional
    public InvoiceItemResponseDTO createInvoiceItem(InvoiceItemRequestDTO dto, Locale locale) {
        if (dto.getBillingInvoiceId() == null) {
            throw new BusinessException("invoiceitem.invoiceRequired");
        }
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
        Treatment related = resolveRelatedService(dto.getRelatedServiceId(), invoice);

        // apply updates
        item.setBillingInvoice(invoice);
        item.setItemDescription(dto.getItemDescription());
        item.setQuantity(dto.getQuantity());
        item.setUnitPrice(dto.getUnitPrice());
        item.setItemCategory(dto.getItemCategory());
        item.setAssignment(assignment);
        item.setRelatedService(related);
        // totalPrice recalculated by @PreUpdate

        InvoiceItem saved = invoiceItemRepository.save(item);

        // recompute invoice total
        billingInvoiceService.recomputeAndPersistTotals(invoice.getId());

        return invoiceItemMapper.toInvoiceItemResponseDTO(saved);
    }

    @Override
    @Transactional
    public void deleteInvoiceItem(UUID id, Locale locale) {
        InvoiceItem item = invoiceItemRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(INVOICE_ITEM_NOT_FOUND_KEY, id));
        UUID invoiceId = item.getBillingInvoice().getId();
        BillingInvoice invoice = writableInvoice(invoiceId, INVOICE_ITEM_NOT_FOUND_KEY, id);
        invoice.getInvoiceItems().remove(item);
        invoiceItemRepository.delete(item);

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
            throw new BusinessException("billing.invoice.cancelledLocked");
        }
        // QA D147: a settled invoice is a closed record (its receipt was
        // issued against these lines). Reverse the payment to amend it.
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new BusinessException("billing.invoice.paidLinesLocked");
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
            return assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(userId, hospitalId)
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
}
