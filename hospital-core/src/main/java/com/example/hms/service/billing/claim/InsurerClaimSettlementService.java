package com.example.hms.service.billing.claim;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.ClaimRefusalClass;
import com.example.hms.enums.ClaimRefusalResolution;
import com.example.hms.enums.ClaimWriteOffReason;
import com.example.hms.enums.ClaimWriteOffStatus;
import com.example.hms.enums.InsurerPaymentStatus;
import com.example.hms.enums.PayerShareStatus;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.InsurerClaimBatch;
import com.example.hms.model.InsurerClaimLine;
import com.example.hms.model.InsurerClaimRefusal;
import com.example.hms.model.InsurerClaimWriteOff;
import com.example.hms.model.InsurerPayment;
import com.example.hms.model.InsurerPaymentAllocation;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.InvoicePayerShare;
import com.example.hms.model.Payer;
import com.example.hms.payload.dto.billing.InsurerClaimBatchDTO;
import com.example.hms.payload.dto.billing.InsurerClaimPagesDTO;
import com.example.hms.payload.dto.billing.InsurerClaimRefusalRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimWriteOffDTO;
import com.example.hms.payload.dto.billing.InsurerClaimWriteOffRequestDTO;
import com.example.hms.payload.dto.billing.InsurerPaymentAllocationRequestDTO;
import com.example.hms.payload.dto.billing.InsurerPaymentDTO;
import com.example.hms.payload.dto.billing.InsurerPaymentRequestDTO;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InsurerClaimBatchRepository;
import com.example.hms.repository.InsurerClaimLineRepository;
import com.example.hms.repository.InsurerClaimRefusalRepository;
import com.example.hms.repository.InsurerClaimWriteOffRepository;
import com.example.hms.repository.InsurerPaymentAllocationRepository;
import com.example.hms.repository.InsurerPaymentRepository;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.repository.PayerRepository;
import com.example.hms.utility.InvoiceBalance;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Insurer claims plan PRs 3 and 4 (V456 to V458, QA D3983): the payer's answer to a bordereau.
 *
 * <ul>
 *   <li><b>Payments</b> ({@link #recordPayment}, {@link #allocate}, {@link #reversePayment}): a remittance has an
 *       amount, the day it was received, a method and a reference; it is put on bordereau lines of the same payer,
 *       wholly or in part, and what is not allocated stays on the payment. Recorded by the hospital admin or the
 *       accountant only (owner decision of 2026-11-26), never through a till; reversed, with a reason, by another
 *       person than the one who recorded it.</li>
 *   <li><b>Refusals</b> ({@link #refuse}): all or part of a line, with the class of the payer's reason. Only a
 *       reason on the patient's side can be re-billed to the patient, and only by the hospital admin or the
 *       accountant: the amount then leaves the invoice's payer share and joins what the patient owes. A provider
 *       fault is sent again or written off; a tariff reduction is a contractual adjustment.</li>
 *   <li><b>Write-offs</b> ({@link #requestWriteOff}, {@link #approveWriteOff}, {@link #refuseWriteOff},
 *       {@link #cancelWriteOff}): asked with a reason code and a supporting document, decided by another person
 *       holding the hospital admin or accountant role. Never changes what the patient owes.</li>
 * </ul>
 *
 * Every amount moves through {@link InsurerClaimLedger}, under the row locks of the bordereau, the share and the
 * line, so {@code claimed = paid + re-billed + written off + outstanding} holds on every line and share. Each
 * request carries a key: a retry answers with what was already recorded.
 */
@Service
@RequiredArgsConstructor
public class InsurerClaimSettlementService {

    static final String PAYMENT_NOT_FOUND = "billing.insurerPayment.notFound";
    static final String WRITE_OFF_NOT_FOUND = "billing.claim.writeOff.notFound";
    private static final String FIELD_AMOUNT = "amount";
    private static final String FIELD_ALLOCATIONS = "allocations";
    private static final String FIELD_REASON = "reason";
    private static final String REASON_REQUIRED = "billing.claim.reasonRequired";
    private static final int REFERENCE_MAX = 120;
    private static final int DOCUMENT_MAX = 200;
    private static final UUID NO_ID = new UUID(0L, 0L);

    private final InsurerPaymentRepository paymentRepository;
    private final InsurerPaymentAllocationRepository allocationRepository;
    private final InsurerClaimRefusalRepository refusalRepository;
    private final InsurerClaimWriteOffRepository writeOffRepository;
    private final InsurerClaimLineRepository lineRepository;
    private final InsurerClaimBatchRepository batchRepository;
    private final BillingInvoiceRepository invoiceRepository;
    private final InvoiceItemRepository itemRepository;
    private final PayerRepository payerRepository;
    private final InsurerClaimLedger ledger;
    private final InsurerClaimBatchService batchService;
    private final InsurerClaimAccess access;

    /* --------------------------------------------------------------- payments */

    /** Records a payer's remittance and, in the same transaction, the lines it settles. */
    @Transactional
    public InsurerPaymentDTO recordPayment(InsurerPaymentRequestDTO body) {
        InsurerClaimAccess.Actor actor = access.requireFinance();
        InsurerPayment replay = paymentRepository
            .findByHospitalIdAndClientRequestId(actor.hospitalId(), body.getClientRequestId()).orElse(null);
        if (replay != null) {
            boolean same = replay.getPayerId().equals(body.getPayerId()) && body.getAmount() != null
                && replay.getAmount().compareTo(body.getAmount()) == 0;
            if (!same) {
                throw new ConflictException(MessageUtil.resolve("billing.claim.clientRequestReused"));
            }
            return paymentDto(replay, actor.userId(), true);
        }
        Payer payer = payerRepository.findByIdAndHospitalId(body.getPayerId(), actor.hospitalId())
            .orElseThrow(() -> new FieldBusinessException("payerId", "billing.claim.payerNotFound"));
        BigDecimal amount = InsurerClaimAccess.requireWholePositive(body.getAmount(), FIELD_AMOUNT);
        if (body.getReceivedDate().isAfter(access.today(actor.hospitalId()))) {
            throw new FieldBusinessException("receivedDate", "billing.claim.dateInFuture");
        }
        String reference = InsurerClaimAccess.requireText(body.getReference(), "reference", REFERENCE_MAX,
            "billing.insurerPayment.referenceRequired");
        String note = InsurerClaimAccess.optionalText(body.getNote(), "note", InsurerClaimAccess.TEXT_MAX);
        if (paymentRepository.existsRecordedReference(actor.hospitalId(), payer.getId(),
                reference.toLowerCase(Locale.ROOT))) {
            throw new FieldBusinessException("reference", "billing.insurerPayment.referenceUsed");
        }
        Map<UUID, BigDecimal> wanted = allocationsOf(body.getAllocations());
        BigDecimal toAllocate = wanted.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (toAllocate.compareTo(amount) > 0) {
            throw new FieldBusinessException(FIELD_ALLOCATIONS, "billing.insurerPayment.exceedsPayment",
                InsurerClaimAccess.francs(amount));
        }
        LocalDateTime at = access.now();
        InsurerPayment payment;
        try {
            payment = paymentRepository.saveAndFlush(InsurerPayment.builder()
                .hospitalId(actor.hospitalId())
                .payerId(payer.getId())
                .amount(amount)
                .receivedDate(body.getReceivedDate())
                .method(body.getMethod())
                .reference(reference)
                .note(note)
                .status(InsurerPaymentStatus.RECORDED)
                .recordedBy(actor.userId())
                .recordedAt(at)
                .clientRequestId(body.getClientRequestId())
                .build());
        } catch (DataIntegrityViolationException ex) {
            // The same key, or the same reference, recorded at the same moment by another request.
            throw new ConflictException(MessageUtil.resolve("billing.claim.clientRequestReused"));
        }
        apply(payment, wanted, actor, at);
        access.audit(AuditEventType.INSURER_PAYMENT_RECORDED, actor, null, "Insurer payment recorded", "payment",
            payment.getId());
        return paymentDto(payment, actor.userId(), true);
    }

    /** Puts money of a recorded payment, still to allocate, on bordereau lines of the same payer. */
    @Transactional
    public InsurerPaymentDTO allocate(UUID paymentId, List<InsurerPaymentAllocationRequestDTO> allocations) {
        InsurerClaimAccess.Actor actor = access.requireFinance();
        InsurerPayment payment = paymentRepository.lockByIdAndHospitalId(paymentId, actor.hospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(PAYMENT_NOT_FOUND));
        if (payment.getStatus() != InsurerPaymentStatus.RECORDED) {
            throw new ConflictException(MessageUtil.resolve("billing.insurerPayment.reversed"));
        }
        Map<UUID, BigDecimal> wanted = allocationsOf(allocations);
        if (wanted.isEmpty()) {
            throw new FieldBusinessException(FIELD_ALLOCATIONS, "billing.insurerPayment.allocationsRequired");
        }
        BigDecimal left = payment.getAmount().subtract(
            allocationRepository.sumLiveOfPaymentAtHospital(payment.getId(), actor.hospitalId()));
        BigDecimal toAllocate = wanted.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (toAllocate.compareTo(left) > 0) {
            throw new FieldBusinessException(FIELD_ALLOCATIONS, "billing.insurerPayment.exceedsPayment",
                InsurerClaimAccess.francs(left.max(BigDecimal.ZERO)));
        }
        apply(payment, wanted, actor, access.now());
        access.audit(AuditEventType.INSURER_PAYMENT_RECORDED, actor, null, "Insurer payment allocated", "payment",
            payment.getId());
        return paymentDto(payment, actor.userId(), true);
    }

    /** Undoes a payment entered in error, with its allocations: by another person than the one who recorded it. */
    @Transactional
    public InsurerPaymentDTO reversePayment(UUID paymentId, String rawReason) {
        InsurerClaimAccess.Actor actor = access.requireFinance();
        String reason = InsurerClaimAccess.requireText(rawReason, FIELD_REASON, InsurerClaimAccess.TEXT_MAX,
            REASON_REQUIRED);
        InsurerPayment payment = paymentRepository.lockByIdAndHospitalId(paymentId, actor.hospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(PAYMENT_NOT_FOUND));
        if (payment.getStatus() != InsurerPaymentStatus.RECORDED) {
            throw new ConflictException(MessageUtil.resolve("billing.insurerPayment.reversed"));
        }
        if (actor.userId().equals(payment.getRecordedBy())) {
            throw new ConflictException(MessageUtil.resolve("billing.insurerPayment.selfReversal"));
        }
        LocalDateTime at = access.now();
        List<InsurerPaymentAllocation> live = allocationRepository
            .findByPaymentIdAndHospitalIdOrderByAllocatedAtAscIdAsc(payment.getId(), actor.hospitalId()).stream()
            .filter(allocation -> allocation.getVoidedAt() == null)
            .toList();
        if (!live.isEmpty()) {
            Set<UUID> lineIds = live.stream().map(InsurerPaymentAllocation::getClaimLineId)
                .collect(Collectors.toSet());
            InsurerClaimLedger.Locked locked = ledger.lock(lineIds, actor.hospitalId());
            for (InsurerPaymentAllocation allocation : live) {
                InsurerClaimLine line = locked.line(allocation.getClaimLineId());
                ledger.unpay(line, locked.shareOf(line), allocation.getAmount());
                allocation.setVoidedAt(at);
            }
            allocationRepository.saveAll(live);
            ledger.restate(locked, actor.userId(), at);
        }
        payment.setStatus(InsurerPaymentStatus.REVERSED);
        payment.setReversedBy(actor.userId());
        payment.setReversedAt(at);
        payment.setReversalReason(reason);
        paymentRepository.saveAndFlush(payment);
        access.audit(AuditEventType.INSURER_PAYMENT_REVERSED, actor, null, "Insurer payment reversed", "payment",
            payment.getId());
        return paymentDto(payment, actor.userId(), true);
    }

    /** One page of the acting hospital's insurer payments, the latest received first. */
    @Transactional(readOnly = true)
    public InsurerClaimPagesDTO.Payments listPayments(UUID payerId, Integer page, Integer size) {
        UUID hospitalId = access.requireReader();
        int pageSize = size == null || size <= 0 ? 25 : Math.min(size, InsurerClaimBatchService.PAGE_MAX);
        int pageIndex = page == null || page < 0 ? 0 : page;
        Page<InsurerPayment> found = paymentRepository.searchAtHospital(hospitalId, payerId == null,
            payerId != null ? payerId : NO_ID, PageRequest.of(pageIndex, pageSize));
        UUID me = access.currentUserId();
        List<InsurerPaymentDTO> rows = found.getContent().stream().map(row -> paymentDto(row, me, false)).toList();
        return new InsurerClaimPagesDTO.Payments(rows, pageIndex, pageSize, found.getTotalElements(),
            found.getTotalPages());
    }

    /** One insurer payment of the acting hospital with its allocations. */
    @Transactional(readOnly = true)
    public InsurerPaymentDTO getPayment(UUID paymentId) {
        UUID hospitalId = access.requireReader();
        InsurerPayment payment = paymentRepository.findByIdAndHospitalId(paymentId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(PAYMENT_NOT_FOUND));
        return paymentDto(payment, access.currentUserId(), true);
    }

    /* --------------------------------------------------------------- refusals */

    /** Records that the payer refused all or part of a line, and what the hospital does with it. */
    @Transactional
    public InsurerClaimBatchDTO refuse(UUID lineId, InsurerClaimRefusalRequestDTO body) {
        InsurerClaimAccess.Actor actor = access.requireBuilder();
        ClaimRefusalClass reasonClass = body.getReasonClass();
        ClaimRefusalResolution resolution = body.getResolution();
        if (!reasonClass.allows(resolution)) {
            throw new FieldBusinessException("resolution", "billing.claim.refusal.resolutionNotAllowed");
        }
        if (resolution == ClaimRefusalResolution.REBILL_PATIENT
                && !access.isFinance(actor.userId(), actor.hospitalId())) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.claim.refusal.rebillFinanceOnly"));
        }
        BigDecimal amount = InsurerClaimAccess.requireWholePositive(body.getAmount(), FIELD_AMOUNT);
        String reason = InsurerClaimAccess.requireText(body.getReason(), FIELD_REASON, InsurerClaimAccess.TEXT_MAX,
            REASON_REQUIRED);
        InsurerClaimLineRepository.Ref ref = refOf(lineId, actor.hospitalId());
        InsurerClaimRefusal replay = refusalRepository
            .findByHospitalIdAndClientRequestId(actor.hospitalId(), body.getClientRequestId()).orElse(null);
        if (replay != null) {
            if (!replay.getClaimLineId().equals(lineId) || replay.getAmount().compareTo(amount) != 0) {
                throw new ConflictException(MessageUtil.resolve("billing.claim.clientRequestReused"));
            }
            return detail(ref.batchId(), actor.hospitalId());
        }
        ClaimWriteOffReason writeOffReason = null;
        String document = null;
        if (resolution == ClaimRefusalResolution.WRITE_OFF_REQUEST) {
            writeOffReason = writeOffReasonFor(reasonClass, body.getWriteOffReason());
            document = InsurerClaimAccess.requireText(body.getSupportingDocument(), "supportingDocument",
                DOCUMENT_MAX, "billing.claim.writeOff.documentRequired");
        }
        // The invoice first, then the bordereau: the order a credit note takes them in.
        BillingInvoice invoice = resolution == ClaimRefusalResolution.REBILL_PATIENT
            ? invoiceRepository.findLockedById(ref.invoiceId())
                .filter(found -> found.getHospital() != null
                    && actor.hospitalId().equals(found.getHospital().getId()))
                .orElseThrow(() -> new ResourceNotFoundException(InsurerClaimLedger.LINE_NOT_FOUND))
            : null;
        InsurerClaimLedger.Locked locked = ledger.lock(List.of(lineId), actor.hospitalId());
        InsurerClaimLine line = locked.line(lineId);
        if (!InsurerClaimLedger.fits(line, amount)) {
            throw new FieldBusinessException(FIELD_AMOUNT, "billing.claim.exceedsLine",
                InsurerClaimAccess.francs(line.availableAmount().max(BigDecimal.ZERO)));
        }
        LocalDateTime at = access.now();
        UUID writeOffId = null;
        if (resolution == ClaimRefusalResolution.REBILL_PATIENT) {
            rebill(invoice, line, locked.shareOf(line), amount, reason);
        } else if (resolution == ClaimRefusalResolution.WRITE_OFF_REQUEST) {
            writeOffId = openWriteOff(line, amount, writeOffReason, reason, document, body.getClientRequestId(),
                actor, at).getId();
        }
        try {
            refusalRepository.saveAndFlush(InsurerClaimRefusal.builder()
                .hospitalId(actor.hospitalId())
                .claimLineId(line.getId())
                .shareId(line.getShareId())
                .amount(amount)
                .reasonClass(reasonClass)
                .reason(reason)
                .resolution(resolution)
                .writeOffId(writeOffId)
                .recordedBy(actor.userId())
                .recordedAt(at)
                .clientRequestId(body.getClientRequestId())
                .build());
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.clientRequestReused"));
        }
        ledger.restate(locked, actor.userId(), at);
        access.audit(AuditEventType.INSURER_CLAIM_LINE_REFUSED, actor, line.getBatchId(),
            "Insurer claim line refused by the payer", "line", line.getId());
        if (resolution == ClaimRefusalResolution.REBILL_PATIENT) {
            access.audit(AuditEventType.INSURER_CLAIM_LINE_REBILLED, actor, line.getBatchId(),
                "Refused amount re-billed to the patient", "line", line.getId());
        }
        return detail(line.getBatchId(), actor.hospitalId());
    }

    /**
     * The refused amount becomes the patient's: it leaves the line, the share and the invoice's payer share (on the
     * invoice line too, so the lines still add up to the invoice's payer share), and the invoice's status follows
     * what the patient has paid of the larger share. Nothing else on the invoice or at a till changes.
     */
    private void rebill(BillingInvoice invoice, InsurerClaimLine line, InvoicePayerShare share, BigDecimal amount,
                        String reason) {
        // A share already refused whole has nothing left to re-bill, and keeps its reason. On a share still
        // standing, the reason kept on the share is the latest refusal's; each refusal keeps its own in its row.
        if (share.getStatus() == PayerShareStatus.REJECTED) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.changedMeanwhile"));
        }
        ledger.rebill(line, share, amount);
        share.setRejectionReason(reason);
        InvoiceItem item = itemRepository.findByIdAndBillingInvoice_IdAndBillingInvoice_Hospital_Id(
                line.getInvoiceItemId(), invoice.getId(), line.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(InsurerClaimLedger.LINE_NOT_FOUND));
        BigDecimal onLine = item.getPayerShareAmount() != null ? item.getPayerShareAmount() : BigDecimal.ZERO;
        if (amount.compareTo(onLine) > 0 || amount.compareTo(InvoiceBalance.payerShareOf(invoice)) > 0) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.changedMeanwhile"));
        }
        item.setPayerShareAmount(onLine.subtract(amount));
        itemRepository.save(item);
        invoice.setPayerShareTotal(InvoiceBalance.payerShareOf(invoice).subtract(amount));
        invoice.setStatus(InvoiceBalance.settledStatusOf(invoice));
        invoiceRepository.save(invoice);
    }

    /** A tariff reduction is always a contractual adjustment; any other class names its own reason code. */
    private static ClaimWriteOffReason writeOffReasonFor(ClaimRefusalClass reasonClass, ClaimWriteOffReason given) {
        if (reasonClass == ClaimRefusalClass.TARIFF_REDUCTION) {
            if (given != null && given != ClaimWriteOffReason.CONTRACTUAL_ADJUSTMENT) {
                throw new FieldBusinessException("writeOffReason", "billing.claim.writeOff.tariffIsAdjustment");
            }
            return ClaimWriteOffReason.CONTRACTUAL_ADJUSTMENT;
        }
        if (given == null) {
            throw new FieldBusinessException("writeOffReason", "billing.claim.writeOff.codeRequired");
        }
        return given;
    }

    /* ------------------------------------------------------------- write-offs */

    /** Asks for an amount the payer will not pay to be given up; another person decides. */
    @Transactional
    public InsurerClaimWriteOffDTO requestWriteOff(UUID lineId, InsurerClaimWriteOffRequestDTO body) {
        InsurerClaimAccess.Actor actor = access.requireBuilder();
        BigDecimal amount = InsurerClaimAccess.requireWholePositive(body.getAmount(), FIELD_AMOUNT);
        String reason = InsurerClaimAccess.requireText(body.getReason(), FIELD_REASON, InsurerClaimAccess.TEXT_MAX,
            REASON_REQUIRED);
        String document = InsurerClaimAccess.requireText(body.getSupportingDocument(), "supportingDocument",
            DOCUMENT_MAX, "billing.claim.writeOff.documentRequired");
        refOf(lineId, actor.hospitalId());
        InsurerClaimWriteOff replay = writeOffRepository
            .findByHospitalIdAndClientRequestId(actor.hospitalId(), body.getClientRequestId()).orElse(null);
        if (replay != null) {
            if (!replay.getClaimLineId().equals(lineId) || replay.getAmount().compareTo(amount) != 0) {
                throw new ConflictException(MessageUtil.resolve("billing.claim.clientRequestReused"));
            }
            return writeOffDtos(List.of(replay), actor.hospitalId(), actor.userId()).get(0);
        }
        InsurerClaimLedger.Locked locked = ledger.lock(List.of(lineId), actor.hospitalId());
        InsurerClaimLine line = locked.line(lineId);
        if (!InsurerClaimLedger.fits(line, amount)) {
            throw new FieldBusinessException(FIELD_AMOUNT, "billing.claim.exceedsLine",
                InsurerClaimAccess.francs(line.availableAmount().max(BigDecimal.ZERO)));
        }
        LocalDateTime at = access.now();
        InsurerClaimWriteOff writeOff = openWriteOff(line, amount, body.getReasonCode(), reason, document,
            body.getClientRequestId(), actor, at);
        ledger.restate(locked, actor.userId(), at);
        return writeOffDtos(List.of(writeOff), actor.hospitalId(), actor.userId()).get(0);
    }

    private InsurerClaimWriteOff openWriteOff(InsurerClaimLine line, BigDecimal amount, ClaimWriteOffReason code,
                                              String reason, String document, UUID clientRequestId,
                                              InsurerClaimAccess.Actor actor, LocalDateTime at) {
        ledger.hold(line, amount);
        InsurerClaimWriteOff writeOff;
        try {
            writeOff = writeOffRepository.saveAndFlush(InsurerClaimWriteOff.builder()
                .hospitalId(actor.hospitalId())
                .claimLineId(line.getId())
                .shareId(line.getShareId())
                .amount(amount)
                .reasonCode(code)
                .reason(reason)
                .supportingDocument(document)
                .status(ClaimWriteOffStatus.REQUESTED)
                .requestedBy(actor.userId())
                .requestedAt(at)
                .clientRequestId(clientRequestId)
                .build());
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.clientRequestReused"));
        }
        access.audit(AuditEventType.INSURER_CLAIM_WRITE_OFF_REQUESTED, actor, line.getBatchId(),
            "Insurer claim write-off requested", "write-off", writeOff.getId());
        return writeOff;
    }

    /** Another hospital admin or accountant approves the write-off: the amount is closed on the line and share. */
    @Transactional
    public InsurerClaimWriteOffDTO approveWriteOff(UUID writeOffId) {
        InsurerClaimAccess.Actor actor = access.requireFinance();
        Decision decision = decidable(writeOffId, actor, true);
        InsurerClaimLine line = decision.line();
        ledger.release(line, decision.writeOff().getAmount());
        ledger.writeOff(line, decision.locked().shareOf(line), decision.writeOff().getAmount());
        return decide(decision, ClaimWriteOffStatus.APPROVED, null, actor,
            AuditEventType.INSURER_CLAIM_WRITE_OFF_APPROVED, "Insurer claim write-off approved");
    }

    /** Another hospital admin or accountant refuses the write-off, with the reason: the amount is owed again. */
    @Transactional
    public InsurerClaimWriteOffDTO refuseWriteOff(UUID writeOffId, String rawReason) {
        InsurerClaimAccess.Actor actor = access.requireFinance();
        String reason = InsurerClaimAccess.requireText(rawReason, FIELD_REASON, InsurerClaimAccess.TEXT_MAX,
            REASON_REQUIRED);
        Decision decision = decidable(writeOffId, actor, true);
        ledger.release(decision.line(), decision.writeOff().getAmount());
        return decide(decision, ClaimWriteOffStatus.REFUSED, reason, actor,
            AuditEventType.INSURER_CLAIM_WRITE_OFF_REFUSED, "Insurer claim write-off refused");
    }

    /** The person who asked, or the hospital admin or accountant, withdraws a write-off not yet decided. */
    @Transactional
    public InsurerClaimWriteOffDTO cancelWriteOff(UUID writeOffId) {
        InsurerClaimAccess.Actor actor = access.requireBuilder();
        Decision decision = decidable(writeOffId, actor, false);
        boolean mine = actor.userId().equals(decision.writeOff().getRequestedBy());
        if (!mine && !access.isFinance(actor.userId(), actor.hospitalId())) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.claim.writeOff.cancelOwnOnly"));
        }
        ledger.release(decision.line(), decision.writeOff().getAmount());
        return decide(decision, ClaimWriteOffStatus.CANCELLED, null, actor,
            AuditEventType.INSURER_CLAIM_WRITE_OFF_CANCELLED, "Insurer claim write-off cancelled");
    }

    /** The write-offs of the acting hospital in the given statuses (those waiting, when none is named). */
    @Transactional(readOnly = true)
    public List<InsurerClaimWriteOffDTO> listWriteOffs(Collection<ClaimWriteOffStatus> statuses) {
        UUID hospitalId = access.requireReader();
        Collection<ClaimWriteOffStatus> wanted = statuses == null || statuses.isEmpty()
            ? EnumSet.of(ClaimWriteOffStatus.REQUESTED) : statuses;
        return writeOffDtos(writeOffRepository.findByHospitalIdAndStatusInOrderByRequestedAtAscIdAsc(hospitalId,
            wanted), hospitalId, access.currentUserId());
    }

    private record Decision(InsurerClaimWriteOff writeOff, InsurerClaimLedger.Locked locked, InsurerClaimLine line) {
    }

    /**
     * The write-off still waiting, read under its bordereau's lock. {@code fourEyes}: the person deciding is never
     * the one who asked.
     */
    private Decision decidable(UUID writeOffId, InsurerClaimAccess.Actor actor, boolean fourEyes) {
        // The line id alone, so the write-off itself is first read under the lock below.
        UUID lineId = writeOffRepository.claimLineIdOf(writeOffId, actor.hospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(WRITE_OFF_NOT_FOUND));
        InsurerClaimLedger.Locked locked = ledger.lock(List.of(lineId), actor.hospitalId());
        // Every step on this write-off is taken under the bordereau's lock, so its status is the stored one.
        InsurerClaimWriteOff writeOff = writeOffRepository.findByIdAndHospitalId(writeOffId, actor.hospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(WRITE_OFF_NOT_FOUND));
        if (writeOff.getStatus() != ClaimWriteOffStatus.REQUESTED) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.writeOff.notRequested"));
        }
        if (fourEyes && actor.userId().equals(writeOff.getRequestedBy())) {
            throw new ConflictException(MessageUtil.resolve("billing.claim.writeOff.selfApproval"));
        }
        return new Decision(writeOff, locked, locked.line(lineId));
    }

    private InsurerClaimWriteOffDTO decide(Decision decision, ClaimWriteOffStatus to, String reason,
                                           InsurerClaimAccess.Actor actor, AuditEventType type, String what) {
        LocalDateTime at = access.now();
        InsurerClaimWriteOff writeOff = decision.writeOff();
        writeOff.setStatus(to);
        writeOff.setDecidedBy(actor.userId());
        writeOff.setDecidedAt(at);
        writeOff.setDecisionReason(reason);
        writeOffRepository.saveAndFlush(writeOff);
        ledger.restate(decision.locked(), actor.userId(), at);
        access.audit(type, actor, decision.line().getBatchId(), what, "write-off", writeOff.getId());
        return writeOffDtos(List.of(writeOff), actor.hospitalId(), actor.userId()).get(0);
    }

    /* ------------------------------------------------------------ internals */

    /** The line's bordereau, share and invoice at the hospital; a line of another hospital is not found. */
    private InsurerClaimLineRepository.Ref refOf(UUID lineId, UUID hospitalId) {
        List<InsurerClaimLineRepository.Ref> refs = lineRepository.refsAtHospital(List.of(lineId), hospitalId);
        if (refs.isEmpty()) {
            throw new ResourceNotFoundException(InsurerClaimLedger.LINE_NOT_FOUND);
        }
        return refs.get(0);
    }

    private InsurerClaimBatchDTO detail(UUID batchId, UUID hospitalId) {
        return batchService.detailOf(batchRepository.findByIdAndHospitalId(batchId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(InsurerClaimBatchService.BATCH_NOT_FOUND)));
    }

    /** Line id to amount, each whole and more than 0; a line named twice is refused. */
    private static Map<UUID, BigDecimal> allocationsOf(List<InsurerPaymentAllocationRequestDTO> allocations) {
        Map<UUID, BigDecimal> wanted = new LinkedHashMap<>();
        if (allocations == null) {
            return wanted;
        }
        for (InsurerPaymentAllocationRequestDTO allocation : allocations) {
            if (allocation == null || allocation.getClaimLineId() == null) {
                throw new FieldBusinessException(FIELD_ALLOCATIONS, "billing.insurerPayment.lineRequired");
            }
            BigDecimal amount = InsurerClaimAccess.requireWholePositive(allocation.getAmount(), FIELD_ALLOCATIONS);
            if (wanted.put(allocation.getClaimLineId(), amount) != null) {
                throw new FieldBusinessException(FIELD_ALLOCATIONS, "billing.insurerPayment.lineTwice");
            }
        }
        return wanted;
    }

    /** Puts {@code wanted} on its lines: each of the payment's payer, each at most what the line still expects. */
    private void apply(InsurerPayment payment, Map<UUID, BigDecimal> wanted, InsurerClaimAccess.Actor actor,
                       LocalDateTime at) {
        if (wanted.isEmpty()) {
            return;
        }
        InsurerClaimLedger.Locked locked = ledger.lock(wanted.keySet(), actor.hospitalId());
        List<InsurerPaymentAllocation> rows = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> entry : wanted.entrySet()) {
            InsurerClaimLine line = locked.line(entry.getKey());
            InsurerClaimBatch batch = locked.batchOf(line);
            if (!batch.getPayerId().equals(payment.getPayerId())) {
                throw new FieldBusinessException(FIELD_ALLOCATIONS, "billing.insurerPayment.otherPayer");
            }
            if (!InsurerClaimLedger.fits(line, entry.getValue())) {
                throw new FieldBusinessException(FIELD_ALLOCATIONS, "billing.insurerPayment.exceedsLine",
                    line.getInvoiceNumber(), InsurerClaimAccess.francs(line.availableAmount().max(BigDecimal.ZERO)));
            }
            ledger.pay(line, locked.shareOf(line), entry.getValue());
            rows.add(InsurerPaymentAllocation.builder()
                .hospitalId(actor.hospitalId())
                .paymentId(payment.getId())
                .claimLineId(line.getId())
                .shareId(line.getShareId())
                .amount(entry.getValue())
                .allocatedBy(actor.userId())
                .allocatedAt(at)
                .build());
        }
        allocationRepository.saveAllAndFlush(rows);
        ledger.restate(locked, actor.userId(), at);
    }

    private InsurerPaymentDTO paymentDto(InsurerPayment payment, UUID me, boolean withAllocations) {
        UUID hospitalId = payment.getHospitalId();
        Map<UUID, String> names = access.names(java.util.Arrays.asList(payment.getRecordedBy(),
            payment.getReversedBy()));
        String payerName = payerRepository.findByIdAndHospitalId(payment.getPayerId(), hospitalId)
            .map(Payer::getName).orElse(null);
        boolean recorded = payment.getStatus() == InsurerPaymentStatus.RECORDED;
        BigDecimal allocated = recorded
            ? allocationRepository.sumLiveOfPaymentAtHospital(payment.getId(), hospitalId) : BigDecimal.ZERO;
        return InsurerPaymentDTO.builder()
            .id(payment.getId())
            .payerId(payment.getPayerId())
            .payerName(payerName)
            .amount(payment.getAmount())
            .receivedDate(payment.getReceivedDate())
            .method(payment.getMethod().name())
            .reference(payment.getReference())
            .note(payment.getNote())
            .status(payment.getStatus().name())
            .allocatedAmount(allocated)
            .unallocatedAmount(recorded ? payment.getAmount().subtract(allocated) : BigDecimal.ZERO)
            .recordedByName(names.get(payment.getRecordedBy()))
            .recordedAt(payment.getRecordedAt())
            .recordedByMe(me != null && me.equals(payment.getRecordedBy()))
            .reversedByName(payment.getReversedBy() != null ? names.get(payment.getReversedBy()) : null)
            .reversedAt(payment.getReversedAt())
            .reversalReason(payment.getReversalReason())
            .allocations(withAllocations ? allocationDtos(payment, hospitalId) : null)
            .build();
    }

    private List<InsurerPaymentDTO.Allocation> allocationDtos(InsurerPayment payment, UUID hospitalId) {
        List<InsurerPaymentAllocation> rows = allocationRepository
            .findByPaymentIdAndHospitalIdOrderByAllocatedAtAscIdAsc(payment.getId(), hospitalId);
        Context context = contextOf(rows.stream().map(InsurerPaymentAllocation::getClaimLineId)
            .collect(Collectors.toSet()), hospitalId);
        List<InsurerPaymentDTO.Allocation> out = new ArrayList<>();
        for (InsurerPaymentAllocation row : rows) {
            InsurerClaimLine line = context.lines().get(row.getClaimLineId());
            InsurerClaimBatch batch = line != null ? context.batches().get(line.getBatchId()) : null;
            out.add(InsurerPaymentDTO.Allocation.builder()
                .id(row.getId())
                .claimLineId(row.getClaimLineId())
                .batchId(line != null ? line.getBatchId() : null)
                .batchNumber(batch != null ? batch.getNumber() : null)
                .invoiceNumber(line != null ? line.getInvoiceNumber() : null)
                .patientName(line != null ? line.getPatientName() : null)
                .actLabel(line != null ? line.getActLabel() : null)
                .amount(row.getAmount())
                .allocatedAt(row.getAllocatedAt())
                .voidedAt(row.getVoidedAt())
                .build());
        }
        return out;
    }

    /** The lines and bordereaux a list of allocations or write-offs names, read once at one hospital. */
    private record Context(Map<UUID, InsurerClaimLine> lines, Map<UUID, InsurerClaimBatch> batches) {
    }

    private Context contextOf(Set<UUID> lineIds, UUID hospitalId) {
        Map<UUID, InsurerClaimLine> lines = new HashMap<>();
        Map<UUID, InsurerClaimBatch> batches = new HashMap<>();
        if (lineIds.isEmpty()) {
            return new Context(lines, batches);
        }
        for (InsurerClaimLine line : lineRepository.findByIdInAndHospitalId(lineIds, hospitalId)) {
            lines.put(line.getId(), line);
        }
        Set<UUID> batchIds = new HashSet<>();
        lines.values().forEach(line -> batchIds.add(line.getBatchId()));
        if (!batchIds.isEmpty()) {
            batchRepository.findByIdInAndHospitalId(batchIds, hospitalId)
                .forEach(batch -> batches.put(batch.getId(), batch));
        }
        return new Context(lines, batches);
    }

    private List<InsurerClaimWriteOffDTO> writeOffDtos(List<InsurerClaimWriteOff> rows, UUID hospitalId, UUID me) {
        Context context = contextOf(rows.stream().map(InsurerClaimWriteOff::getClaimLineId)
            .collect(Collectors.toSet()), hospitalId);
        List<UUID> people = new ArrayList<>();
        rows.forEach(row -> {
            people.add(row.getRequestedBy());
            people.add(row.getDecidedBy());
        });
        Map<UUID, String> names = access.names(people);
        Map<UUID, String> payerNames = new HashMap<>();
        List<InsurerClaimWriteOffDTO> out = new ArrayList<>();
        for (InsurerClaimWriteOff row : rows) {
            InsurerClaimLine line = context.lines().get(row.getClaimLineId());
            InsurerClaimBatch batch = line != null ? context.batches().get(line.getBatchId()) : null;
            String payerName = batch == null ? null : payerNames.computeIfAbsent(batch.getPayerId(),
                id -> payerRepository.findByIdAndHospitalId(id, hospitalId).map(Payer::getName).orElse(""));
            out.add(InsurerClaimWriteOffDTO.builder()
                .id(row.getId())
                .claimLineId(row.getClaimLineId())
                .batchId(batch != null ? batch.getId() : null)
                .batchNumber(batch != null ? batch.getNumber() : null)
                .payerName(payerName)
                .invoiceNumber(line != null ? line.getInvoiceNumber() : null)
                .patientName(line != null ? line.getPatientName() : null)
                .actLabel(line != null ? line.getActLabel() : null)
                .amount(row.getAmount())
                .reasonCode(row.getReasonCode().name())
                .reason(row.getReason())
                .supportingDocument(row.getSupportingDocument())
                .status(row.getStatus().name())
                .requestedByName(names.get(row.getRequestedBy()))
                .requestedAt(row.getRequestedAt())
                .requestedByMe(me != null && me.equals(row.getRequestedBy()))
                .decidedByName(row.getDecidedBy() != null ? names.get(row.getDecidedBy()) : null)
                .decidedAt(row.getDecidedAt())
                .decisionReason(row.getDecisionReason())
                .build());
        }
        return out;
    }
}
