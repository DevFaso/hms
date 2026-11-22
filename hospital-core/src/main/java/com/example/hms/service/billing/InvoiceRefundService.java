package com.example.hms.service.billing;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.CashTill;
import com.example.hms.enums.InvoiceRefundStatus;
import com.example.hms.enums.MobileMoneyOperator;
import com.example.hms.enums.PaymentMethod;
import com.example.hms.enums.PaymentStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.RequiredFields;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.InvoiceRefund;
import com.example.hms.model.PaymentTransaction;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.InvoicePdfResponseDTO;
import com.example.hms.payload.dto.billing.InvoiceRefundDTO;
import com.example.hms.payload.dto.billing.InvoiceRefundPayRequestDTO;
import com.example.hms.payload.dto.billing.InvoiceRefundReasonRequestDTO;
import com.example.hms.payload.dto.billing.InvoiceRefundRequestDTO;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InvoiceRefundRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.PdfInvoiceService;
import com.example.hms.service.payment.PaymentReferenceRegistry;
import com.example.hms.service.payment.TillPaymentMethods;
import com.example.hms.utility.CardNumbers;
import com.example.hms.utility.InvoiceBalance;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import com.example.hms.utility.TransactionCallbacks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Billing-money plan PR 8 (V307, scorecard M3; fixes QA D1900): money handed back to a patient on an invoice.
 *
 * <p><b>Ledger.</b> A refund is the outgoing entry of the billing ledger. The payments that came in are never
 * changed; the invoice counts what went out in {@code amount_refunded}, and the refund is money OUT of the till on the
 * day it is paid ({@code paid_at}), so it rewrites no past day's figures.
 *
 * <p><b>Steps.</b> REQUESTED (a cashier, a receptionist, an accountant or a hospital admin, with the method and the
 * reason), then APPROVED or REJECTED by ANOTHER person holding HOSPITAL_ADMIN or ACCOUNTANT (owner decision D-3, no
 * self-approval), then PAID when the cashier hands it over. A request not yet paid can be CANCELLED with a reason.
 * Every move is a conditional UPDATE (decide, markPaid, markCancelled) under the invoice row lock, so a refund is decided
 * once and paid once, and two requests together never ask for more than is due.
 *
 * <p><b>Who.</b> The roles are checked on the assignment at the acting hospital (never global view), before any
 * lookup: {@code RoleExpansion} gives the platform super-admin the hospital admin authority, so the
 * {@code @PreAuthorize} on the controller alone would let them move money. The super-admin reads only.
 *
 * <p><b>Tenancy.</b> An invoice or refund of another hospital answers as a missing one (404).
 *
 * <p><b>No card data.</b> Reference, reason and decision texts are refused when they hold a card number
 * ({@link CardNumbers}), here and at the request edge ({@code @NoCardNumber}).
 *
 * <p><b>Audit.</b> One row per step after commit, ids only: REFUND_REQUESTED, REFUND_APPROVED, REFUND_REJECTED,
 * REFUND_CANCELLED, and REFUND_ISSUED only when money is paid out.
 */
@Slf4j
@Service
public class InvoiceRefundService {

    static final String INVOICE_NOT_FOUND = "billinginvoice.notfound";
    static final String REFUND_NOT_FOUND = "billing.refund.notFound";
    /** The refusal of a decision on a refund that no longer awaits one. */
    static final String NOT_REQUESTED = "billing.refund.notRequested";
    static final String AUDIT_ENTITY = "BillingInvoice";
    static final int TEXT_MAX = 500;
    static final int REFERENCE_MAX = 120;

    private static final String FIELD_AMOUNT = "amount";
    private static final String FIELD_METHOD = "method";
    private static final String FIELD_OPERATOR = "mobileMoneyOperator";
    private static final String FIELD_REFERENCE = "reference";
    private static final String FIELD_REASON = "reason";

    /** Asked or approved, not yet paid: they hold part of the refund due. */
    static final Set<InvoiceRefundStatus> OPEN = EnumSet.of(InvoiceRefundStatus.REQUESTED,
        InvoiceRefundStatus.APPROVED);
    /** Methods whose money can only be traced by the operator's or the bank's reference. */
    private static final Set<PaymentMethod> REFERENCE_REQUIRED = EnumSet.of(PaymentMethod.MOBILE_MONEY,
        PaymentMethod.BANK_TRANSFER);

    private final BillingInvoiceRepository invoiceRepository;
    private final InvoiceRefundRepository refundRepository;
    private final PaymentReferenceRegistry referenceRegistry;
    private final UserRepository userRepository;
    private final RoleValidator roleValidator;
    private final ActingScopeResolver actingScopeResolver;
    private final PdfInvoiceService pdfInvoiceService;
    private final AuditEventLogService auditEventLogService;
    private final Clock clock;

    @SuppressWarnings("java:S107") // one collaborator per concern; a parameter object would only hide them
    public InvoiceRefundService(BillingInvoiceRepository invoiceRepository,
                                InvoiceRefundRepository refundRepository,
                                PaymentReferenceRegistry referenceRegistry,
                                UserRepository userRepository,
                                RoleValidator roleValidator,
                                ActingScopeResolver actingScopeResolver,
                                PdfInvoiceService pdfInvoiceService,
                                AuditEventLogService auditEventLogService,
                                Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.refundRepository = refundRepository;
        this.referenceRegistry = referenceRegistry;
        this.userRepository = userRepository;
        this.roleValidator = roleValidator;
        this.actingScopeResolver = actingScopeResolver;
        this.pdfInvoiceService = pdfInvoiceService;
        this.auditEventLogService = auditEventLogService;
        this.clock = clock;
    }

    /** QA D2702: the payments a reversal reverses once it is paid out. Setter-injected like the other late additions. */
    private com.example.hms.repository.PaymentTransactionRepository paymentRepository;
    /** Billing plan PR 10 (V309): a refund paid out is money out of the payer's cash session. */
    private com.example.hms.service.billing.cash.CashSessionService cashSessions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPaymentRepository(com.example.hms.repository.PaymentTransactionRepository repository) {
        this.paymentRepository = repository;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCashSessionService(com.example.hms.service.billing.cash.CashSessionService service) {
        this.cashSessions = service;
    }

    // ───────────────────────────── writes ─────────────────────────────

    /**
     * QA D2702: a confirmed payment entered in error is reversed only through the refund approval. This asks for it
     * (REQUESTED, with the reason): the payment stays CONFIRMED and the till still expects its money. ANOTHER
     * hospital admin or accountant approves it ({@link #approve}); the cashier then hands the money back
     * ({@link #pay}), which reverses the payment and counts the money out of that cashier's session.
     */
    @Transactional
    public InvoiceRefundDTO requestReversal(UUID invoiceId, UUID paymentId, String rawReason) {
        Actor actor = requireActor(roleValidator::paysAtBillingDesk, "billing.payment.reversalRequestersOnly");
        BillingInvoice invoice = lockInScope(invoiceId, actor.hospitalId());
        PaymentTransaction payment = loadPayment(paymentId, invoice);
        if (payment.getStatus() != PaymentStatus.CONFIRMED) {
            throw new BusinessException("billing.payment.notConfirmed");
        }
        String reason = trimToNull(rawReason);
        RequiredFields.check()
            .require(reason != null, FIELD_REASON, "billing.payment.reasonRequired")
            .orThrow();
        requireText(reason, FIELD_REASON);
        if (!refundRepository.findLiveReversalOf(payment.getId()).isEmpty()) {
            throw new ConflictException(MessageUtil.resolve("billing.payment.reversalPending"));
        }
        requireReversalRoom(invoice, payment, null);
        InvoiceRefund refund;
        try {
            refund = refundRepository.saveAndFlush(InvoiceRefund.builder()
                .invoice(invoice)
                .hospitalId(actor.hospitalId())
                .amount(payment.getAmount())
                .method(payment.getPaymentMethod())
                .mobileMoneyOperator(payment.getMobileMoneyOperator())
                .reason(reason)
                .status(InvoiceRefundStatus.REQUESTED)
                .requestedBy(actor.userId())
                .requestedAt(now())
                .reversedPaymentId(payment.getId())
                .build());
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            // uq_refund_live_reversal: a second request for the same payment raced this one.
            throw new ConflictException(MessageUtil.resolve("billing.payment.reversalPending"));
        }
        auditAfterCommit(AuditEventType.PAYMENT_REVERSAL_REQUESTED, actor.userId(), invoice.getId(), refund,
            "Payment reversal requested");
        return toDto(refund, actor.userId(), null);
    }

    private PaymentTransaction loadPayment(UUID paymentId, BillingInvoice invoice) {
        if (paymentRepository == null) {
            throw new ResourceNotFoundException("billing.payment.notFound");
        }
        return paymentRepository.findByIdAndInvoice_Id(paymentId, invoice.getId())
            .orElseThrow(() -> new ResourceNotFoundException("billing.payment.notFound"));
    }

    /**
     * A reversal takes the payment's amount out of what was received; refunds already handed back must still fit.
     *
     * <p>PR 10 review: a refund and a reversal never hand the same excess back twice. Invoice 2 000, paid twice: an
     * overpayment refund of 2 000 and the reversal of the second payment were both approved and both paid out, and
     * the hospital kept nothing on a bill of 2 000. While a refund is asked, approved or paid on the invoice, a
     * reversal is refused when what the hospital would keep after it (less the refunds open and the other reversals
     * waiting) is under the patient's share. With no refund on the invoice a payment entered in error is reversed
     * as before, whatever is left owing.
     */
    private void requireReversalRoom(BillingInvoice invoice, PaymentTransaction payment, UUID thisReversalId) {
        BigDecimal paid = invoice.getAmountPaid() != null ? invoice.getAmountPaid() : BigDecimal.ZERO;
        BigDecimal refunded = invoice.getAmountRefunded() != null ? invoice.getAmountRefunded() : BigDecimal.ZERO;
        BigDecimal remaining = paid.subtract(payment.getAmount());
        if (remaining.signum() < 0) {
            throw new BusinessException("billing.payment.ledgerMismatch");
        }
        if (remaining.compareTo(refunded) < 0) {
            throw new BusinessException("billing.payment.reversalBelowRefunded");
        }
        BigDecimal openRefunds = nz(refundRepository.sumByInvoiceAndStatusIn(invoice.getId(), OPEN, null));
        if (refunded.signum() == 0 && openRefunds.signum() == 0) {
            return;
        }
        BigDecimal otherReversals = nz(refundRepository.sumReversalsByInvoiceAndStatusIn(invoice.getId(), OPEN,
            thisReversalId));
        BigDecimal keptAfter = remaining.subtract(refunded).subtract(openRefunds).subtract(otherReversals);
        if (keptAfter.compareTo(InvoiceBalance.patientShareOf(invoice)) < 0) {
            throw new ConflictException(MessageUtil.resolve("billing.payment.reversalWhileRefund"));
        }
    }

    /**
     * PR 10 review, the other order: what a refund may still take, less the reversals asked or approved and not yet
     * paid out (each will lower the amount received by its payment).
     */
    private BigDecimal reversalsWaiting(BillingInvoice invoice) {
        return nz(refundRepository.sumReversalsByInvoiceAndStatusIn(invoice.getId(), OPEN, null));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /** QA D2702: whether this refund reverses a payment. */
    static boolean isReversal(InvoiceRefund refund) {
        return refund.getReversedPaymentId() != null;
    }

    /** Asks for {@code body.amount} to be given back to the patient (REQUESTED). */
    @Transactional
    public InvoiceRefundDTO request(UUID invoiceId, InvoiceRefundRequestDTO body) {
        Actor actor = requireActor(roleValidator::canRequestRefund, "billing.refund.requestersOnly");
        InvoiceRefundRequestDTO request = body != null ? body : new InvoiceRefundRequestDTO();
        BillingInvoice invoice = lockInScope(invoiceId, actor.hospitalId());

        // A retried submit carrying the same key is answered with the refund already asked for, checked under
        // the invoice lock.
        InvoiceRefund replay = replayOf(request, invoice, actor.hospitalId());
        if (replay != null) {
            return toDto(replay, actor.userId(), null);
        }

        String reason = trimToNull(request.getReason());
        String reference = trimToNull(request.getReference());
        RequiredFields.check()
            .require(request.getAmount() != null, FIELD_AMOUNT, "billing.refund.amountRequired")
            .require(request.getMethod() != null && !request.getMethod().isBlank(), FIELD_METHOD,
                "billing.payment.methodRequired")
            .require(reason != null, FIELD_REASON, "billing.refund.reasonRequired")
            .orThrow();
        BigDecimal amount = requireWholePositive(request.getAmount());
        PaymentMethod method = parseMethod(request.getMethod());
        // An insurer's share is never money at the till, in or out (QA D1611).
        TillPaymentMethods.requireReceivedAtTill(method, FIELD_METHOD);
        // PR 8 review: the operator and its reference are known when the money is sent; both are required at
        // payout. Given now, they are kept (an operator only for mobile money).
        MobileMoneyOperator operator = method == PaymentMethod.MOBILE_MONEY ? request.getMobileMoneyOperator() : null;
        requireText(reason, FIELD_REASON);
        if (reference != null) {
            if (reference.length() > REFERENCE_MAX) {
                throw new FieldBusinessException(FIELD_REFERENCE, "billing.refund.referenceTooLong");
            }
            CardNumbers.requireNone(reference, FIELD_REFERENCE);
        }

        BigDecimal due = InvoiceBalance.refundDueOf(invoice);
        if (due.signum() <= 0) {
            throw new FieldBusinessException(FIELD_AMOUNT, "billing.refund.nothingDue");
        }
        // PR 10 review: a reversal waiting will take its payment out of the money received; it holds that much.
        BigDecimal open = nz(refundRepository.sumByInvoiceAndStatusIn(invoice.getId(), OPEN, null))
            .add(reversalsWaiting(invoice));
        BigDecimal refundable = InvoiceBalance.refundableOf(invoice, open);
        if (amount.compareTo(refundable) > 0) {
            throw new FieldBusinessException(FIELD_AMOUNT, "billing.refund.exceedsRefundable", francs(refundable));
        }
        if (method != PaymentMethod.CASH && reference != null) {
            // A refund never reuses a payment's operator reference (nor another refund's): QA D1129 class.
            referenceRegistry.requireUnused(actor.hospitalId(), reference, FIELD_REFERENCE);
        }

        InvoiceRefund refund = saveRequest(InvoiceRefund.builder()
            .invoice(invoice)
            .hospitalId(actor.hospitalId())
            .amount(amount)
            .method(method)
            .mobileMoneyOperator(operator)
            .reference(reference)
            .reason(reason)
            .status(InvoiceRefundStatus.REQUESTED)
            .requestedBy(actor.userId())
            .requestedAt(now())
            .clientRequestId(request.getClientRequestId())
            .build(), request.getClientRequestId());
        auditAfterCommit(AuditEventType.REFUND_REQUESTED, actor.userId(), invoice.getId(), refund,
            "Refund requested");
        return toDto(refund, actor.userId(), null);
    }

    /** Another hospital admin or accountant approves a requested refund (owner decision D-3). */
    @Transactional
    public InvoiceRefundDTO approve(UUID invoiceId, UUID refundId) {
        Actor actor = requireActor(roleValidator::canApproveRefund, "billing.refund.approversOnly");
        BillingInvoice invoice = lockInScope(invoiceId, actor.hospitalId());
        InvoiceRefund refund = loadRefund(refundId, invoice, actor.hospitalId());
        requireDecidable(refund, actor);
        if (isReversal(refund)) {
            // QA D2702: the payment must still be counted, and its reversal still fit what refunds handed back.
            PaymentTransaction payment = loadPayment(refund.getReversedPaymentId(), invoice);
            if (payment.getStatus() != PaymentStatus.CONFIRMED) {
                throw new ConflictException(MessageUtil.resolve("billing.payment.notConfirmed"));
            }
            requireReversalRoom(invoice, payment, refund.getId());
        } else {
            // The balance may have moved since the request (a reversal, a line added): the request must still fit.
            BigDecimal others = nz(refundRepository.sumByInvoiceAndStatusIn(invoice.getId(), OPEN, refund.getId()))
                .add(reversalsWaiting(invoice));
            BigDecimal room = InvoiceBalance.refundableOf(invoice, others);
            if (refund.getAmount().compareTo(room) > 0) {
                throw new ConflictException(MessageUtil.resolve("billing.refund.noLongerDue", francs(room)));
            }
        }
        LocalDateTime at = now();
        claimed(refundRepository.decide(refund.getId(), refund.getHospitalId(), InvoiceRefundStatus.APPROVED,
            actor.userId(), at, null), NOT_REQUESTED);
        refund.setStatus(InvoiceRefundStatus.APPROVED);
        refund.setDecidedBy(actor.userId());
        refund.setDecidedAt(at);
        auditAfterCommit(AuditEventType.REFUND_APPROVED, actor.userId(), invoice.getId(), refund,
            "Refund approved");
        return toDto(refund, actor.userId(), null);
    }

    /** Another hospital admin or accountant rejects a requested refund, with the reason. */
    @Transactional
    public InvoiceRefundDTO reject(UUID invoiceId, UUID refundId, InvoiceRefundReasonRequestDTO body) {
        Actor actor = requireActor(roleValidator::canApproveRefund, "billing.refund.approversOnly");
        BillingInvoice invoice = lockInScope(invoiceId, actor.hospitalId());
        InvoiceRefund refund = loadRefund(refundId, invoice, actor.hospitalId());
        requireDecidable(refund, actor);
        String reason = trimToNull(body != null ? body.getReason() : null);
        RequiredFields.check()
            .require(reason != null, FIELD_REASON, "billing.refund.rejectReasonRequired")
            .orThrow();
        requireText(reason, FIELD_REASON);
        LocalDateTime at = now();
        claimed(refundRepository.decide(refund.getId(), refund.getHospitalId(), InvoiceRefundStatus.REJECTED,
            actor.userId(), at, reason), NOT_REQUESTED);
        refund.setStatus(InvoiceRefundStatus.REJECTED);
        refund.setDecidedBy(actor.userId());
        refund.setDecidedAt(at);
        refund.setDecisionReason(reason);
        auditAfterCommit(AuditEventType.REFUND_REJECTED, actor.userId(), invoice.getId(), refund,
            "Refund rejected");
        return toDto(refund, actor.userId(), null);
    }

    /**
     * The cashier records that the approved refund was handed to the patient. The invoice's amount refunded goes up
     * under its row lock; the refund is money out of this cashier's till today.
     */
    @Transactional
    public InvoiceRefundDTO pay(UUID invoiceId, UUID refundId, InvoiceRefundPayRequestDTO body) {
        Actor actor = requireActor(roleValidator::canPayRefund, "billing.refund.payersOnly");
        BillingInvoice invoice = lockInScope(invoiceId, actor.hospitalId());
        InvoiceRefund refund = loadRefund(refundId, invoice, actor.hospitalId());
        if (refund.getStatus() != InvoiceRefundStatus.APPROVED) {
            throw new ConflictException(MessageUtil.resolve("billing.refund.notApproved"));
        }
        boolean reversal = isReversal(refund);
        PaymentTransaction reversed = null;
        if (reversal) {
            reversed = loadPayment(refund.getReversedPaymentId(), invoice);
            requireReversalRoom(invoice, reversed, refund.getId());
        } else {
            BigDecimal due = InvoiceBalance.refundableOf(invoice, reversalsWaiting(invoice));
            if (refund.getAmount().compareTo(due) > 0) {
                throw new ConflictException(MessageUtil.resolve("billing.refund.noLongerDue", francs(due)));
            }
        }
        Handover handover = handover(refund, body, actor.hospitalId());
        // Billing plan PR 10: the money leaves the payer's till today; a closed till refuses it (nothing written).
        CashTill till = roleValidator.paysAtBillingDesk(actor.userId(), actor.hospitalId())
            ? CashTill.BILLING : CashTill.RECEPTION;
        // QA D3410 (r70): from a till the payer holds open today, and never below its cash.
        String payoutMethod = refund.getMethod() != null ? refund.getMethod().name() : null;
        UUID cashSessionId = cashSessions == null ? null
            : cashSessions.stampPayout(till, actor.hospitalId(), actor.userId(), payoutMethod, refund.getAmount());
        // PR 8 review: the receipt number is assigned once, here, under the invoice lock, in payout order.
        int receiptSeq = refundRepository.maxReceiptSeq(invoice.getId()) + 1;
        String receiptNumber = invoice.getInvoiceNumber() + "-RB" + receiptSeq;
        LocalDateTime at = now();
        claimed(refundRepository.markPaid(refund.getId(), refund.getHospitalId(), new InvoiceRefundRepository.Payout(
            actor.userId(), at, handover.reference(), handover.operator(), receiptSeq, receiptNumber, cashSessionId)),
            "billing.refund.notApproved");
        if (reversal) {
            reversePaidOut(invoice, reversed, refund, actor.userId(), at);
        } else {
            BigDecimal refunded = invoice.getAmountRefunded() != null ? invoice.getAmountRefunded() : BigDecimal.ZERO;
            invoice.setAmountRefunded(refunded.add(refund.getAmount()));
        }
        invoiceRepository.save(invoice);
        refund.setCashSessionId(cashSessionId);
        refund.setStatus(InvoiceRefundStatus.PAID);
        refund.setPaidBy(actor.userId());
        refund.setPaidAt(at);
        refund.setReference(handover.reference());
        refund.setMobileMoneyOperator(handover.operator());
        refund.setReceiptSeq(receiptSeq);
        refund.setReceiptNumber(receiptNumber);
        auditAfterCommit(AuditEventType.REFUND_ISSUED, actor.userId(), invoice.getId(), refund,
            "Refund paid out");
        return toDto(refund, actor.userId(), null);
    }

    /**
     * QA D2702: the approved reversal handed back: the payment becomes REVERSED (a conditional UPDATE, so it is
     * reversed once), the invoice's amount paid and status follow, and the payment names the refund that took the
     * money out. The payment's own session keeps the money in on the day it came in.
     */
    private void reversePaidOut(BillingInvoice invoice, PaymentTransaction payment, InvoiceRefund refund,
                                UUID actorId, LocalDateTime at) {
        if (paymentRepository.transitionStatus(payment.getId(), invoice.getId(), PaymentStatus.CONFIRMED,
                PaymentStatus.REVERSED) != 1) {
            throw new ConflictException(MessageUtil.resolve("billing.payment.notConfirmed"));
        }
        BigDecimal paid = invoice.getAmountPaid() != null ? invoice.getAmountPaid() : BigDecimal.ZERO;
        invoice.setAmountPaid(paid.subtract(payment.getAmount()));
        invoice.setStatus(InvoiceBalance.settledStatusOf(invoice));
        payment.setStatus(PaymentStatus.REVERSED);
        payment.setReversedBy(actorId);
        payment.setReversedAt(at);
        payment.setReversalReason(refund.getReason());
        payment.setReversalRefundId(refund.getId());
        paymentRepository.save(payment);
        auditAfterCommit(AuditEventType.PAYMENT_REVERSED, actorId, invoice.getId(), refund,
            "Payment reversed");
    }

    /**
     * A refund not yet paid is withdrawn, with a reason: a request by the person who asked for it, an approved one
     * by that person or by a hospital admin or accountant. A paid, rejected or cancelled refund stays as it is.
     */
    @Transactional
    public InvoiceRefundDTO cancel(UUID invoiceId, UUID refundId, InvoiceRefundReasonRequestDTO body) {
        Actor actor = requireActor((user, hospital) -> roleValidator.canRequestRefund(user, hospital)
            || roleValidator.canApproveRefund(user, hospital), "billing.refund.requestersOnly");
        BillingInvoice invoice = lockInScope(invoiceId, actor.hospitalId());
        InvoiceRefund refund = loadRefund(refundId, invoice, actor.hospitalId());
        InvoiceRefundStatus from = refund.getStatus();
        // A paid, rejected or cancelled refund keeps its outcome and its reason (QA D1186 class).
        if (refund.getStatus() != InvoiceRefundStatus.REQUESTED && refund.getStatus() != InvoiceRefundStatus.APPROVED) {
            throw new ConflictException(MessageUtil.resolve("billing.refund.notCancellable"));
        }
        boolean requester = actor.userId().equals(refund.getRequestedBy());
        boolean approver = from == InvoiceRefundStatus.APPROVED
            && roleValidator.canApproveRefund(actor.userId(), actor.hospitalId());
        if (!requester && !approver) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("billing.refund.cancelRequesterOnly"));
        }
        String reason = trimToNull(body != null ? body.getReason() : null);
        RequiredFields.check()
            .require(reason != null, FIELD_REASON, "billing.refund.cancelReasonRequired")
            .orThrow();
        requireText(reason, FIELD_REASON);
        LocalDateTime at = now();
        claimed(refundRepository.markCancelled(refund.getId(), refund.getHospitalId(), from, actor.userId(), at,
            reason), "billing.refund.notCancellable");
        refund.setStatus(InvoiceRefundStatus.CANCELLED);
        refund.setCancelledBy(actor.userId());
        refund.setCancelledAt(at);
        refund.setCancelReason(reason);
        auditAfterCommit(AuditEventType.REFUND_CANCELLED, actor.userId(), invoice.getId(), refund,
            "Refund cancelled");
        return toDto(refund, actor.userId(), null);
    }

    // ───────────────────────────── reads ─────────────────────────────

    /** Every refund of an invoice, oldest first. */
    @Transactional(readOnly = true)
    public List<InvoiceRefundDTO> list(UUID invoiceId) {
        UUID me = roleValidator.getCurrentUserId();
        BillingInvoice invoice = readableInvoice(invoiceId);
        UUID hospitalId = invoice.getHospital().getId();
        List<InvoiceRefund> rows = refundRepository.findByInvoice_IdAndHospitalIdOrderByCreatedAtAsc(invoice.getId(),
            hospitalId);
        Map<UUID, String> names = names(rows);
        return rows.stream().map(r -> toDto(r, me, names)).toList();
    }

    /**
     * The hospital's refunds waiting on someone: REQUESTED (the approvers' queue, "Remboursements à approuver") and
     * APPROVED (to hand over at the till). Oldest first, with the invoice number and the patient's name.
     */
    @Transactional(readOnly = true)
    public List<InvoiceRefundDTO> queue(Collection<InvoiceRefundStatus> statuses) {
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID me = roleValidator.getCurrentUserId();
        requireReader(me, hospitalId);
        Collection<InvoiceRefundStatus> wanted = statuses == null || statuses.isEmpty() ? OPEN : statuses;
        List<InvoiceRefund> rows = refundRepository.findByHospitalIdAndStatusInOrderByCreatedAtAsc(hospitalId, wanted);
        Map<UUID, String> names = names(rows);
        return rows.stream().map(r -> {
            InvoiceRefundDTO dto = toDto(r, me, names);
            BillingInvoice invoice = r.getInvoice();
            dto.setPatientName(invoice != null && invoice.getPatient() != null ? invoice.getPatient().getFullName()
                : null);
            return dto;
        }).toList();
    }

    /** "Reçu de remboursement": the receipt of a refund paid out, with the patient's signature line. */
    @Transactional(readOnly = true)
    public InvoicePdfResponseDTO receipt(UUID invoiceId, UUID refundId, Locale locale) {
        BillingInvoice invoice = readableInvoice(invoiceId);
        UUID hospitalId = invoice.getHospital().getId();
        InvoiceRefund refund = loadRefund(refundId, invoice, hospitalId);
        if (refund.getStatus() != InvoiceRefundStatus.PAID) {
            throw new ConflictException(MessageUtil.resolve("billing.refund.receiptOnlyPaid"));
        }
        // PR 8 review: the number stored at payout, never recomputed (a later payout cannot renumber it).
        String number = refund.getReceiptNumber();
        Locale effective = locale != null ? locale : LocaleContextHolder.getLocale();
        byte[] pdf = pdfInvoiceService.generateRefundReceiptPdf(invoice, refund, number,
            displayName(refund.getPaidBy()), reversalDetails(invoice, refund), effective);
        return new InvoicePdfResponseDTO(pdf, number, invoice.getId());
    }

    /**
     * QA D3253 (r70): a payment reversal's receipt names the receipt of the payment it reverses (numbered as the
     * payment receipt numbers it: the invoice number and its rank among the money received, by confirmation time),
     * its reason and its approver. Null for a refund of an overpayment.
     */
    private PdfInvoiceService.ReversalDetails reversalDetails(BillingInvoice invoice, InvoiceRefund refund) {
        if (!isReversal(refund)) {
            return null;
        }
        String reversedReceipt = null;
        if (paymentRepository != null) {
            List<PaymentTransaction> received = paymentRepository.findByInvoice_IdOrderByCreatedAtAsc(invoice.getId())
                .stream()
                .filter(p -> p.getStatus() == com.example.hms.enums.PaymentStatus.CONFIRMED
                    || p.getStatus() == com.example.hms.enums.PaymentStatus.REVERSED)
                .sorted(java.util.Comparator.comparing(InvoiceRefundService::receivedAt,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                    .thenComparing(p -> String.valueOf(p.getId())))
                .toList();
            for (int i = 0; i < received.size(); i++) {
                if (received.get(i).getId().equals(refund.getReversedPaymentId())) {
                    reversedReceipt = invoice.getInvoiceNumber() + "-R" + (i + 1);
                }
            }
        }
        return new PdfInvoiceService.ReversalDetails(reversedReceipt, refund.getReason(),
            displayName(refund.getDecidedBy()));
    }

    private static LocalDateTime receivedAt(PaymentTransaction payment) {
        return payment.getConfirmedAt() != null ? payment.getConfirmedAt() : payment.getCreatedAt();
    }

    // ───────────────────────────── access ─────────────────────────────

    /** Who acts, at which hospital. */
    record Actor(UUID userId, UUID hospitalId) {
    }

    /** The acting hospital (pinned, never global view) when the caller holds one of the step's roles there. */
    private Actor requireActor(BiPredicate<UUID, UUID> holdsRole, String refusalKey) {
        // PR 8 review: a support session impersonating a user never moves money as that user (the four-eyes rule
        // would be one agent with two accounts). The paths refuse it first (ImpersonationLimits); this is the second
        // line, for any caller that does not come through them.
        com.example.hms.security.context.ImpersonationLimits.refuseMoneyWrite(() -> auditEventLogService, "refund");
        UUID hospitalId = actingScopeResolver.requirePinned();
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null || !holdsRole.test(userId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve(refusalKey));
        }
        return new Actor(userId, hospitalId);
    }

    /** A reader at {@code hospitalId}: one of the refund roles there, or the verified platform super-admin. */
    private void requireReader(UUID userId, UUID hospitalId) {
        if (actingScopeResolver.isVerifiedSuperAdmin()) {
            return;
        }
        if (userId == null || !(roleValidator.canRequestRefund(userId, hospitalId)
                || roleValidator.canApproveRefund(userId, hospitalId))) {
            throw new AccessDeniedException("Access denied");
        }
    }

    /**
     * The invoice the caller may read refunds of: at the acting hospital, or any hospital in the verified
     * super-admin's global view. Another hospital's invoice answers as a missing one.
     */
    private BillingInvoice readableInvoice(UUID invoiceId) {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        UUID me = roleValidator.getCurrentUserId();
        BillingInvoice invoice = invoiceRepository.findById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(INVOICE_NOT_FOUND, invoiceId));
        UUID invoiceHospital = invoice.getHospital() != null ? invoice.getHospital().getId() : null;
        if (hospitalId == null) {
            if (!actingScopeResolver.isVerifiedSuperAdmin()) {
                throw new AccessDeniedException("Access denied");
            }
            return invoice;
        }
        if (!hospitalId.equals(invoiceHospital)) {
            throw new ResourceNotFoundException(INVOICE_NOT_FOUND, invoiceId);
        }
        requireReader(me, hospitalId);
        return invoice;
    }

    /** The invoice locked, at the actor's hospital; another hospital's answers as a missing one. */
    private BillingInvoice lockInScope(UUID invoiceId, UUID hospitalId) {
        BillingInvoice invoice = invoiceRepository.findLockedById(invoiceId)
            .orElseThrow(() -> new ResourceNotFoundException(INVOICE_NOT_FOUND, invoiceId));
        if (invoice.getHospital() == null || !hospitalId.equals(invoice.getHospital().getId())) {
            throw new ResourceNotFoundException(INVOICE_NOT_FOUND, invoiceId);
        }
        return invoice;
    }

    private InvoiceRefund loadRefund(UUID refundId, BillingInvoice invoice, UUID hospitalId) {
        return refundRepository.findByIdAndInvoice_IdAndHospitalId(refundId, invoice.getId(), hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(REFUND_NOT_FOUND, refundId));
    }

    /** A requested refund, decided by someone other than the person who asked (D-3). */
    private static void requireDecidable(InvoiceRefund refund, Actor actor) {
        if (refund.getStatus() != InvoiceRefundStatus.REQUESTED) {
            throw new ConflictException(MessageUtil.resolve(NOT_REQUESTED));
        }
        if (actor.userId().equals(refund.getRequestedBy())) {
            throw new ConflictException(MessageUtil.resolve("billing.refund.selfApproval"));
        }
    }

    /**
     * The conditional UPDATE is the guarantee: a concurrent or repeated step updates nothing and is refused. The
     * managed row is then set to what the statement wrote, so the rest of the transaction reads it as stored.
     */
    private static void claimed(int updated, String refusalKey) {
        if (updated != 1) {
            throw new ConflictException(MessageUtil.resolve(refusalKey));
        }
    }

    // ───────────────────────────── values ─────────────────────────────

    /** The reference and operator recorded when the money is handed over. */
    record Handover(String reference, MobileMoneyOperator operator) {
    }

    /**
     * PR 8 review: mobile money and bank transfer are sent at payout, so their reference is required now (and the
     * operator for mobile money: billing.payment.operatorRequired, as a payment). A value given at the request is
     * kept when the payout repeats or omits it; a new reference goes through the registry and the card check.
     */
    private Handover handover(InvoiceRefund refund, InvoiceRefundPayRequestDTO body, UUID hospitalId) {
        PaymentMethod method = refund.getMethod();
        String given = trimToNull(body != null ? body.getReference() : null);
        String reference = given != null ? given : refund.getReference();
        MobileMoneyOperator operator = body != null && body.getMobileMoneyOperator() != null
            ? body.getMobileMoneyOperator() : refund.getMobileMoneyOperator();
        operator = com.example.hms.service.payment.MobileMoneyOperators.require(method, operator, FIELD_OPERATOR);
        if (REFERENCE_REQUIRED.contains(method) && reference == null) {
            throw new FieldBusinessException(FIELD_REFERENCE, "billing.refund.referenceRequired");
        }
        if (given != null && !given.equals(refund.getReference())) {
            if (given.length() > REFERENCE_MAX) {
                throw new FieldBusinessException(FIELD_REFERENCE, "billing.refund.referenceTooLong");
            }
            CardNumbers.requireNone(given, FIELD_REFERENCE);
            if (method != PaymentMethod.CASH) {
                referenceRegistry.requireUnused(hospitalId, given, FIELD_REFERENCE);
            }
        }
        return new Handover(reference, operator);
    }

    /**
     * The refund a retried submit repeats, or null for a new request. PR 8 review: a key reused for another invoice,
     * or for another amount or method, is refused, never answered with the refund it first named.
     */
    private InvoiceRefund replayOf(InvoiceRefundRequestDTO request, BillingInvoice invoice, UUID hospitalId) {
        if (request.getClientRequestId() == null) {
            return null;
        }
        InvoiceRefund replay = refundRepository
            .findByHospitalIdAndClientRequestId(hospitalId, request.getClientRequestId()).orElse(null);
        if (replay != null
                && (!Objects.equals(replay.getInvoice().getId(), invoice.getId()) || !sameRequest(replay, request))) {
            throw new ConflictException(MessageUtil.resolve("billing.refund.clientRequestReused"));
        }
        return replay;
    }

    /** A replay names the same amount and method as the request it repeats. */
    private static boolean sameRequest(InvoiceRefund replay, InvoiceRefundRequestDTO request) {
        boolean sameAmount = request.getAmount() != null && replay.getAmount() != null
            && request.getAmount().compareTo(replay.getAmount()) == 0;
        boolean sameMethod = request.getMethod() != null && replay.getMethod() != null
            && replay.getMethod().name().equalsIgnoreCase(request.getMethod().trim());
        return sameAmount && sameMethod;
    }

    /**
     * Saves a new request. PR 8 review: two submits with one key racing on two invoices meet the per-hospital
     * unique index; the loser is refused as a reused key, not a 500.
     */
    private InvoiceRefund saveRequest(InvoiceRefund refund, UUID clientRequestId) {
        try {
            return refundRepository.saveAndFlush(refund);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            if (clientRequestId != null) {
                throw new ConflictException(MessageUtil.resolve("billing.refund.clientRequestReused"));
            }
            throw ex;
        }
    }

    private static BigDecimal requireWholePositive(BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new FieldBusinessException(FIELD_AMOUNT, "billing.refund.amountPositive");
        }
        if (amount.stripTrailingZeros().scale() > 0) {
            throw new FieldBusinessException(FIELD_AMOUNT, "billing.payment.wholeAmountOnly");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static PaymentMethod parseMethod(String raw) {
        for (PaymentMethod candidate : PaymentMethod.values()) {
            if (candidate.name().equalsIgnoreCase(raw.trim())) {
                return candidate;
            }
        }
        throw new FieldBusinessException(FIELD_METHOD, "billing.payment.methodUnsupported");
    }


    /** A reason: at most 500 characters, never a card number. */
    private static void requireText(String text, String field) {
        if (text.length() > TEXT_MAX) {
            throw new FieldBusinessException(field, "billing.refund.reasonTooLong");
        }
        CardNumbers.requireNone(text, field);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Whole francs with the locale's grouping (spaces, never a no-break space), as the billing refusals print. */
    public static String francs(BigDecimal amount) {
        java.text.NumberFormat format = java.text.NumberFormat.getIntegerInstance(LocaleContextHolder.getLocale());
        format.setGroupingUsed(true);
        return format.format(amount.setScale(0, RoundingMode.HALF_UP).longValueExact())
            .replace('\u00a0', ' ').replace('\u202f', ' ');
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // ───────────────────────────── mapping ─────────────────────────────

    private Map<UUID, String> names(List<InvoiceRefund> rows) {
        Set<UUID> ids = new HashSet<>();
        for (InvoiceRefund r : rows) {
            addIfPresent(ids, r.getRequestedBy());
            addIfPresent(ids, r.getDecidedBy());
            addIfPresent(ids, r.getPaidBy());
            addIfPresent(ids, r.getCancelledBy());
        }
        Map<UUID, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (User user : userRepository.findAllById(ids)) {
                names.put(user.getId(), fullName(user));
            }
        }
        return names;
    }

    private static void addIfPresent(Set<UUID> ids, UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }

    private String displayName(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).map(InvoiceRefundService::fullName).orElse(null);
    }

    private static String fullName(User user) {
        String first = user.getFirstName() != null ? user.getFirstName() : "";
        String last = user.getLastName() != null ? user.getLastName() : "";
        String joined = (first + " " + last).trim();
        return joined.isEmpty() ? null : joined;
    }

    /** {@code names} null: the names are read for this one refund. */
    private InvoiceRefundDTO toDto(InvoiceRefund r, UUID me, Map<UUID, String> names) {
        Map<UUID, String> known = names != null ? names : names(List.of(r));
        BillingInvoice invoice = r.getInvoice();
        return InvoiceRefundDTO.builder()
            .id(r.getId())
            .invoiceId(invoice != null ? invoice.getId() : null)
            .invoiceNumber(invoice != null ? invoice.getInvoiceNumber() : null)
            .amount(r.getAmount())
            .method(r.getMethod() != null ? r.getMethod().name() : null)
            .mobileMoneyOperator(r.getMobileMoneyOperator() != null ? r.getMobileMoneyOperator().name() : null)
            .reference(r.getReference())
            .reason(r.getReason())
            .status(r.getStatus() != null ? r.getStatus().name() : null)
            .requestedBy(r.getRequestedBy())
            .requestedByName(known.get(r.getRequestedBy()))
            .requestedAt(r.getRequestedAt())
            .requestedByMe(me != null && me.equals(r.getRequestedBy()))
            .decidedBy(r.getDecidedBy())
            .decidedByName(r.getDecidedBy() != null ? known.get(r.getDecidedBy()) : null)
            .decidedAt(r.getDecidedAt())
            .decisionReason(r.getDecisionReason())
            .paidBy(r.getPaidBy())
            .paidByName(r.getPaidBy() != null ? known.get(r.getPaidBy()) : null)
            .paidAt(r.getPaidAt())
            .receiptNumber(r.getReceiptNumber())
            .cancelledBy(r.getCancelledBy())
            .cancelledByName(r.getCancelledBy() != null ? known.get(r.getCancelledBy()) : null)
            .cancelledAt(r.getCancelledAt())
            .cancelReason(r.getCancelReason())
            .reversedPaymentId(r.getReversedPaymentId())
            .build();
    }

    // ───────────────────────────── audit ─────────────────────────────

    /** Ids only (no amount tied to a person, no reason text, no PHI), written after commit. */
    private void auditAfterCommit(AuditEventType type, UUID actorId, UUID invoiceId, InvoiceRefund refund, String what) {
        if (auditEventLogService == null) {
            return;
        }
        String invoiceIdText = String.valueOf(invoiceId);
        String description = what + ": refund " + refund.getId() + " on invoice " + invoiceIdText;
        // QA D2924 (r66): the refund's hospital anchors the row for the hospital admin's audit log.
        UUID hospitalId = refund.getHospitalId();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .eventType(type)
                    .status(AuditStatus.SUCCESS)
                    .userId(actorId)
                    .hospitalId(hospitalId)
                    .eventDescription(description)
                    .entityType(AUDIT_ENTITY)
                    .resourceId(invoiceIdText)
                    .build());
            } catch (RuntimeException ex) {
                log.warn("Refund audit {} not written for invoice {}: {}", type, invoiceIdText,
                    ex.getClass().getSimpleName());
            }
        });
    }
}
