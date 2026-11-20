package com.example.hms.service.billing;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.CashTill;
import com.example.hms.enums.InvoiceRefundStatus;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.PaymentMethod;
import com.example.hms.enums.PaymentStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceRefund;
import com.example.hms.model.Patient;
import com.example.hms.model.PaymentTransaction;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.billing.InvoiceRefundDTO;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InvoiceRefundRepository;
import com.example.hms.repository.PaymentTransactionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.PdfInvoiceService;
import com.example.hms.service.billing.cash.CashSessionService;
import com.example.hms.service.payment.PaymentReferenceRegistry;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D2702 (r65, High): a payment reversal was a one-person refund. Any cashier, accountant or admin reversed any
 * confirmed payment alone, handed the money back, and the till's expected cash dropped to match. A reversal is now
 * asked for with a reason, approved by ANOTHER hospital admin or accountant (the refund rule, D-3), and only the
 * payout reverses the payment and counts the money out of the payer's cash session.
 */
@DisplayName("Payment reversal: asked, approved by another person, then paid out")
class PaymentReversalApprovalTest {

    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID BILLING_A = UUID.randomUUID();
    private static final UUID BILLING_2_A = UUID.randomUUID();
    private static final UUID ACCOUNTANT_A = UUID.randomUUID();
    private static final UUID RECEPTION_A = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();

    private final BillingInvoiceRepository invoices = mock(BillingInvoiceRepository.class);
    private final InvoiceRefundRepository refunds = mock(InvoiceRefundRepository.class);
    private final PaymentTransactionRepository payments = mock(PaymentTransactionRepository.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final CashSessionService sessions = mock(CashSessionService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);

    private final Map<UUID, InvoiceRefund> ledger = new HashMap<>();
    private InvoiceRefundService service;
    private BillingInvoice invoice;
    private PaymentTransaction payment;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        service = new InvoiceRefundService(invoices, refunds, mock(PaymentReferenceRegistry.class),
            mock(UserRepository.class), roles, scope, mock(PdfInvoiceService.class), audit, clock);
        service.setPaymentRepository(payments);
        service.setCashSessionService(sessions);
        when(scope.requirePinned()).thenReturn(HOSPITAL_A);
        when(roles.paysAtBillingDesk(any(), eq(HOSPITAL_A))).thenAnswer(call -> {
            UUID user = call.getArgument(0);
            return user.equals(BILLING_A) || user.equals(BILLING_2_A) || user.equals(ACCOUNTANT_A);
        });
        when(roles.canApproveRefund(any(), eq(HOSPITAL_A))).thenAnswer(call -> ACCOUNTANT_A.equals(call.getArgument(0)));
        when(roles.canPayRefund(any(), eq(HOSPITAL_A))).thenAnswer(call -> {
            UUID user = call.getArgument(0);
            return user.equals(BILLING_A) || user.equals(BILLING_2_A) || user.equals(RECEPTION_A);
        });
        when(sessions.stamp(any(), any(), any())).thenReturn(SESSION);

        Hospital hospital = new Hospital();
        hospital.setId(HOSPITAL_A);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        invoice = BillingInvoice.builder().hospital(hospital).patient(patient).invoiceNumber("TWP-2026-000026")
            .totalAmount(new BigDecimal("18000")).amountPaid(new BigDecimal("2000")).status(InvoiceStatus.PARTIALLY_PAID)
            .build();
        invoice.setId(UUID.randomUUID());
        when(invoices.findLockedById(invoice.getId())).thenReturn(Optional.of(invoice));
        payment = PaymentTransaction.builder().invoice(invoice).amount(new BigDecimal("1000"))
            .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.CONFIRMED).recordedBy(BILLING_2_A).build();
        payment.setId(UUID.randomUUID());
        when(payments.findByIdAndInvoice_Id(payment.getId(), invoice.getId())).thenReturn(Optional.of(payment));
        when(payments.transitionStatus(payment.getId(), invoice.getId(), PaymentStatus.CONFIRMED,
            PaymentStatus.REVERSED)).thenAnswer(call -> payment.getStatus() == PaymentStatus.CONFIRMED ? 1 : 0);

        when(refunds.saveAndFlush(any(InvoiceRefund.class))).thenAnswer(call -> {
            InvoiceRefund r = call.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            ledger.put(r.getId(), r);
            return r;
        });
        when(refunds.findLiveReversalOf(any())).thenAnswer(call -> ledger.values().stream()
            .filter(r -> call.getArgument(0).equals(r.getReversedPaymentId()))
            .filter(r -> r.getStatus() != InvoiceRefundStatus.REJECTED && r.getStatus() != InvoiceRefundStatus.CANCELLED)
            .toList());
        when(refunds.findByIdAndInvoice_IdAndHospitalId(any(), any(), any())).thenAnswer(call ->
            Optional.ofNullable(ledger.get(call.<UUID>getArgument(0))));
        when(refunds.decide(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            InvoiceRefund r = ledger.get(call.<UUID>getArgument(0));
            if (r == null || r.getStatus() != InvoiceRefundStatus.REQUESTED) {
                return 0;
            }
            r.setStatus(call.getArgument(2));
            return 1;
        });
        when(refunds.markPaid(any(), any(), any())).thenAnswer(call -> {
            InvoiceRefund r = ledger.get(call.<UUID>getArgument(0));
            if (r == null || r.getStatus() != InvoiceRefundStatus.APPROVED) {
                return 0;
            }
            r.setStatus(InvoiceRefundStatus.PAID);
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    private void actAs(UUID user) {
        when(roles.getCurrentUserId()).thenReturn(user);
    }

    private InvoiceRefundDTO asked() {
        actAs(BILLING_A);
        return service.requestReversal(invoice.getId(), payment.getId(), "Saisie en double");
    }

    @Test
    @DisplayName("asking changes no money: the payment stays CONFIRMED and the invoice keeps its amount paid")
    void askingMovesNoMoney() {
        InvoiceRefundDTO reversal = asked();

        assertThat(reversal.getStatus()).isEqualTo("REQUESTED");
        assertThat(reversal.getReversedPaymentId()).isEqualTo(payment.getId());
        assertThat(reversal.getAmount()).isEqualByComparingTo("1000");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(invoice.getAmountPaid()).isEqualByComparingTo("2000");
        verify(payments, never()).transitionStatus(any(), any(), any(), any());
        verify(sessions, never()).stamp(any(), any(), any());
    }

    @Test
    @DisplayName("a reason is required; a second request for the same payment is refused")
    void reasonRequiredAndOneLiveReversal() {
        actAs(BILLING_A);
        UUID invoiceId = invoice.getId();
        UUID paymentId = payment.getId();
        assertThatThrownBy(() -> service.requestReversal(invoiceId, paymentId, "  "))
            .isInstanceOf(FieldBusinessException.class);
        asked();
        assertThatThrownBy(() -> service.requestReversal(invoiceId, paymentId, "Encore"))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a receptionist cannot ask for a reversal")
    void receptionistCannotAsk() {
        actAs(RECEPTION_A);
        UUID invoiceId = invoice.getId();
        UUID paymentId = payment.getId();
        assertThatThrownBy(() -> service.requestReversal(invoiceId, paymentId, "Erreur"))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
    }

    @Test
    @DisplayName("the requester never approves their own reversal; a cashier cannot approve at all")
    void fourEyes() {
        actAs(ACCOUNTANT_A);
        InvoiceRefundDTO own = service.requestReversal(invoice.getId(), payment.getId(), "Erreur de saisie");
        UUID invoiceId = invoice.getId();
        UUID ownId = own.getId();
        assertThatThrownBy(() -> service.approve(invoiceId, ownId)).isInstanceOf(ConflictException.class);
        actAs(BILLING_2_A);
        assertThatThrownBy(() -> service.approve(invoiceId, ownId))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
    }

    @Test
    @DisplayName("an approved reversal cannot be paid before approval; once paid the payment is REVERSED, money out of the till")
    void payoutReversesThePaymentAndCountsMoneyOut() {
        InvoiceRefundDTO reversal = asked();
        UUID invoiceId = invoice.getId();
        UUID reversalId = reversal.getId();
        actAs(BILLING_A);
        assertThatThrownBy(() -> service.pay(invoiceId, reversalId, null)).isInstanceOf(ConflictException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);

        actAs(ACCOUNTANT_A);
        service.approve(invoiceId, reversal.getId());
        actAs(BILLING_A);
        InvoiceRefundDTO paid = service.pay(invoiceId, reversal.getId(), null);

        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REVERSED);
        assertThat(payment.getReversalRefundId()).isEqualTo(reversal.getId());
        assertThat(invoice.getAmountPaid()).isEqualByComparingTo("1000");
        // A reversal is not an overpayment refund: what was refunded on the invoice is unchanged.
        assertThat(invoice.getAmountRefunded()).isEqualByComparingTo("0");
        verify(sessions).stamp(CashTill.BILLING, HOSPITAL_A, BILLING_A);
        ArgumentCaptor<InvoiceRefundRepository.Payout> payout = ArgumentCaptor.forClass(InvoiceRefundRepository.Payout.class);
        verify(refunds).markPaid(eq(reversal.getId()), eq(HOSPITAL_A), payout.capture());
        assertThat(payout.getValue().cashSessionId()).isEqualTo(SESSION);
    }

    @Test
    @DisplayName("a payment already reversed meanwhile is never reversed twice")
    void reversedOnce() {
        InvoiceRefundDTO reversal = asked();
        UUID invoiceId = invoice.getId();
        UUID reversalId = reversal.getId();
        actAs(ACCOUNTANT_A);
        service.approve(invoiceId, reversal.getId());
        payment.setStatus(PaymentStatus.REVERSED);
        actAs(BILLING_A);
        assertThatThrownBy(() -> service.pay(invoiceId, reversalId, null))
            .isInstanceOf(ConflictException.class);
        assertThat(invoice.getAmountPaid()).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("a reversal cannot take the money held below what refunds already handed back")
    void notBelowWhatWasRefunded() {
        invoice.setTotalAmount(new BigDecimal("1500"));
        invoice.setAmountPaid(new BigDecimal("2000"));
        invoice.setAmountRefunded(new BigDecimal("500"));
        payment.setAmount(new BigDecimal("2000"));
        actAs(BILLING_A);
        UUID invoiceId = invoice.getId();
        UUID paymentId = payment.getId();
        assertThatThrownBy(() -> service.requestReversal(invoiceId, paymentId, "Erreur"))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getMessageKey()).isEqualTo("billing.payment.reversalBelowRefunded"));
    }

    @Test
    @DisplayName("a payment that is not confirmed cannot be reversed")
    void onlyConfirmed() {
        payment.setStatus(PaymentStatus.PENDING);
        actAs(BILLING_A);
        UUID invoiceId = invoice.getId();
        UUID paymentId = payment.getId();
        assertThatThrownBy(() -> service.requestReversal(invoiceId, paymentId, "Erreur"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("audit: the request, then PAYMENT_REVERSED and REFUND_ISSUED at payout")
    void audited() {
        InvoiceRefundDTO reversal = asked();
        actAs(ACCOUNTANT_A);
        service.approve(invoice.getId(), reversal.getId());
        actAs(BILLING_A);
        service.pay(invoice.getId(), reversal.getId(), null);
        ArgumentCaptor<AuditEventRequestDTO> events = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit, atLeastOnce()).logEvent(events.capture());
        List<AuditEventType> types = events.getAllValues().stream().map(AuditEventRequestDTO::getEventType).toList();
        assertThat(types).containsSubsequence(AuditEventType.PAYMENT_REVERSAL_REQUESTED,
            AuditEventType.REFUND_APPROVED, AuditEventType.PAYMENT_REVERSED, AuditEventType.REFUND_ISSUED);
    }

    @Test
    @DisplayName("an approval is refused once the payment is no longer counted; the requester may withdraw it")
    void approvalChecksThePaymentAndTheRequesterMayWithdraw() {
        InvoiceRefundDTO reversal = asked();
        UUID invoiceId = invoice.getId();
        UUID reversalId = reversal.getId();
        payment.setStatus(PaymentStatus.REVERSED);
        actAs(ACCOUNTANT_A);
        assertThatThrownBy(() -> service.approve(invoiceId, reversalId)).isInstanceOf(ConflictException.class);
        payment.setStatus(PaymentStatus.CONFIRMED);
        when(refunds.markCancelled(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            InvoiceRefund r = ledger.get(call.<UUID>getArgument(0));
            r.setStatus(InvoiceRefundStatus.CANCELLED);
            return 1;
        });
        when(roles.canRequestRefund(BILLING_A, HOSPITAL_A)).thenReturn(true);
        actAs(BILLING_A);
        InvoiceRefundDTO withdrawn = service.cancel(invoiceId, reversalId,
            com.example.hms.payload.dto.billing.InvoiceRefundReasonRequestDTO.builder().reason("Erreur").build());
        assertThat(withdrawn.getStatus()).isEqualTo("CANCELLED");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        // Withdrawn, the payment can be asked about again.
        assertThat(service.requestReversal(invoiceId, payment.getId(), "Saisie en double").getStatus())
            .isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("a reversal handed back by a receptionist leaves the reception till")
    void receptionPayoutLeavesTheReceptionTill() {
        InvoiceRefundDTO reversal = asked();
        actAs(ACCOUNTANT_A);
        service.approve(invoice.getId(), reversal.getId());
        actAs(RECEPTION_A);
        service.pay(invoice.getId(), reversal.getId(), null);
        verify(sessions).stamp(CashTill.RECEPTION, HOSPITAL_A, RECEPTION_A);
    }
}
