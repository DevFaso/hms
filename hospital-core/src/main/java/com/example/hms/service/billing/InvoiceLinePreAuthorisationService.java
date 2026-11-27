package com.example.hms.service.billing;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.InvoiceItem;
import com.example.hms.payload.dto.billing.InvoiceLinePreAuthorisationDTO;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.security.context.ImpersonationLimits;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.ImagingOrderService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Insurer claims plan PR 1 (V383, QA D3984, US-1): the desk or billing records or corrects the insurer's
 * pre-authorisation number of one invoice line. The number obtained after an urgent study used to be recordable by a
 * doctor or a radiologist only, on the imaging order, and never reached the invoice, the claim or the paper.
 *
 * <p>Who: the deciders of a payer share and reception ({@link RoleValidator#canRecordPreAuthorisation}) at the
 * acting hospital, never the super-admin, never a support session (a money path). Any invoice of that hospital but a
 * cancelled one; the line's amounts are not touched. When the line bills an imaging order of the same hospital, the
 * order takes the number too, which clears its « préautorisation en attente » and closes the billing follow-up task.
 */
@Service
public class InvoiceLinePreAuthorisationService {

    private static final String INVOICE_NOT_FOUND = "billinginvoice.notfound";

    private final BillingInvoiceRepository invoiceRepository;
    private final InvoiceItemRepository itemRepository;
    private final InvoiceLinePreAuthorisations preAuthorisations;
    private final ImagingOrderService imagingOrderService;
    private final RoleValidator roleValidator;
    private final ActingScopeResolver actingScopeResolver;
    private final AuditEventLogService auditEventLogService;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per table or check the write touches
    public InvoiceLinePreAuthorisationService(BillingInvoiceRepository invoiceRepository,
                                              InvoiceItemRepository itemRepository,
                                              InvoiceLinePreAuthorisations preAuthorisations,
                                              ImagingOrderService imagingOrderService,
                                              RoleValidator roleValidator,
                                              ActingScopeResolver actingScopeResolver,
                                              AuditEventLogService auditEventLogService,
                                              Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.itemRepository = itemRepository;
        this.preAuthorisations = preAuthorisations;
        this.imagingOrderService = imagingOrderService;
        this.roleValidator = roleValidator;
        this.actingScopeResolver = actingScopeResolver;
        this.auditEventLogService = auditEventLogService;
        this.clock = clock;
    }

    @Transactional
    public InvoiceLinePreAuthorisationDTO recordOnLine(UUID invoiceId, UUID itemId, String rawNumber) {
        ImpersonationLimits.refuseMoneyWrite(() -> auditEventLogService, "pre-authorisation number");
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null || !roleValidator.canRecordPreAuthorisation(userId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.preAuthorisation.recordersOnly"));
        }
        String number = InvoiceLinePreAuthorisations.normalise(rawNumber, InvoiceLinePreAuthorisations.FIELD);
        // The invoice's row lock, as every other write on its lines takes it first.
        BillingInvoice invoice = invoiceRepository.findLockedById(invoiceId)
            .filter(found -> found.getHospital() != null && hospitalId.equals(found.getHospital().getId()))
            .orElseThrow(() -> new ResourceNotFoundException(INVOICE_NOT_FOUND, invoiceId));
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new ConflictException(MessageUtil.resolve("billing.preAuthorisation.invoiceCancelled"));
        }
        InvoiceItem line = itemRepository.findByIdAndBillingInvoice_IdAndBillingInvoice_Hospital_Id(itemId, invoiceId,
                hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("billing.preAuthorisation.lineNotFound"));
        LocalDateTime at = LocalDateTime.now(clock);
        itemRepository.recordPreAuthorisation(List.of(line.getId()), hospitalId, number, userId, at);
        preAuthorisations.auditLine(userId, hospitalId, invoiceId, line.getId());
        boolean orderUpdated = BillableOrderType.IMAGING_ORDER.name().equals(line.getSourceOrderType())
            && line.getSourceOrderId() != null
            && imagingOrderService.recordAuthorizationFromInvoiceLine(line.getSourceOrderId(), hospitalId, number);
        return new InvoiceLinePreAuthorisationDTO(line.getId(), invoiceId, number, at, orderUpdated);
    }
}
