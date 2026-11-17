package com.example.hms.service.billing;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.InvoiceRefundStatus;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.MobileMoneyOperator;
import com.example.hms.enums.PaymentMethod;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceRefund;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
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
import com.example.hms.utility.InvoiceBalance;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 8 (QA D1900, owner decision D-3): the refund steps, their roles, the four-eyes rule, the
 * amounts, tenancy and the audit, over an in-memory refund ledger whose conditional UPDATE behaves like the real one.
 */
@DisplayName("Invoice refunds: request, approve by another person, pay out")
class InvoiceRefundServiceTest {

    private static final UUID HOSPITAL_A = UUID.randomUUID();
    private static final UUID HOSPITAL_B = UUID.randomUUID();
    private static final UUID BILLING_A = UUID.randomUUID();
    private static final UUID ADMIN_A = UUID.randomUUID();
    private static final UUID ACCOUNTANT_A = UUID.randomUUID();
    private static final UUID RECEPTION_A = UUID.randomUUID();

    private final BillingInvoiceRepository invoices = mock(BillingInvoiceRepository.class);
    private final InvoiceRefundRepository refunds = mock(InvoiceRefundRepository.class);
    private final PaymentReferenceRegistry registry = mock(PaymentReferenceRegistry.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);
    private final PdfInvoiceService pdf = mock(PdfInvoiceService.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);

    private final Map<UUID, InvoiceRefund> ledger = new HashMap<>();
    private InvoiceRefundService service;
    private BillingInvoice invoice;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        service = new InvoiceRefundService(invoices, refunds, registry, users, roles, scope, pdf, audit, clock);
        when(scope.requirePinned()).thenReturn(HOSPITAL_A);
        when(roles.requireActiveHospitalId()).thenReturn(HOSPITAL_A);
        when(roles.canRequestRefund(any(), eq(HOSPITAL_A))).thenAnswer(call -> {
            UUID user = call.getArgument(0);
            return user.equals(BILLING_A) || user.equals(ADMIN_A) || user.equals(ACCOUNTANT_A)
                || user.equals(RECEPTION_A);
        });
        when(roles.canApproveRefund(any(), eq(HOSPITAL_A))).thenAnswer(call -> {
            UUID user = call.getArgument(0);
            return user.equals(ADMIN_A) || user.equals(ACCOUNTANT_A);
        });
        when(roles.canPayRefund(any(), eq(HOSPITAL_A))).thenAnswer(call -> {
            UUID user = call.getArgument(0);
            return user.equals(BILLING_A) || user.equals(ADMIN_A) || user.equals(RECEPTION_A);
        });
        // BC2 in r53: a 2 000 F copay folded onto a visit priced 1 500 F.
        invoice = invoice(HOSPITAL_A, "1500", "2000");
        // The in-memory ledger: saved refunds get an id; the conditional UPDATE wins only from the stated status.
        when(refunds.saveAndFlush(any(InvoiceRefund.class))).thenAnswer(call -> store(call.getArgument(0)));
        when(refunds.save(any(InvoiceRefund.class))).thenAnswer(call -> store(call.getArgument(0)));
        when(refunds.decide(any(), any(), any(), any(), any(), any())).thenAnswer(call ->
            move(call.getArgument(0), call.getArgument(1), InvoiceRefundStatus.REQUESTED, call.getArgument(2)));
        when(refunds.markPaid(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(call ->
            move(call.getArgument(0), call.getArgument(1), InvoiceRefundStatus.APPROVED, InvoiceRefundStatus.PAID));
        // The receipt rank, read under the invoice lock: the highest given so far on the invoice.
        when(refunds.maxReceiptSeq(any())).thenAnswer(call -> ledger.values().stream()
            .filter(r -> r.getInvoice().getId().equals(call.getArgument(0)) && r.getReceiptSeq() != null)
            .mapToInt(InvoiceRefund::getReceiptSeq).max().orElse(0));
        when(refunds.markCancelled(any(), any(), any(), any(), any(), any())).thenAnswer(call ->
            move(call.getArgument(0), call.getArgument(1), call.getArgument(2), InvoiceRefundStatus.CANCELLED));
        when(refunds.findByIdAndInvoice_IdAndHospitalId(any(), any(), any())).thenAnswer(call ->
            Optional.ofNullable(ledger.get(call.<UUID>getArgument(0)))
                .filter(r -> r.getInvoice().getId().equals(call.getArgument(1))
                    && r.getHospitalId().equals(call.getArgument(2))));
        when(refunds.sumByInvoiceAndStatusIn(any(), any(), any())).thenAnswer(call -> ledger.values().stream()
            .filter(r -> r.getInvoice().getId().equals(call.getArgument(0)))
            .filter(r -> call.<java.util.Collection<InvoiceRefundStatus>>getArgument(1).contains(r.getStatus()))
            .filter(r -> !r.getId().equals(call.getArgument(2)))
            .map(InvoiceRefund::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        when(refunds.findByHospitalIdAndClientRequestId(any(), any())).thenAnswer(call -> ledger.values().stream()
            .filter(r -> call.getArgument(1).equals(r.getClientRequestId())
                && r.getHospitalId().equals(call.getArgument(0)))
            .findFirst());
        when(users.findAllById(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    /** The conditional UPDATE: moves the row only from {@code from}; 1 when it moved, 0 otherwise. */
    private int move(UUID id, UUID hospitalId, InvoiceRefundStatus from, InvoiceRefundStatus to) {
        InvoiceRefund row = ledger.get(id);
        if (row == null || !row.getHospitalId().equals(hospitalId) || row.getStatus() != from) {
            return 0;
        }
        row.setStatus(to);
        return 1;
    }

    private InvoiceRefund store(InvoiceRefund refund) {
        if (refund.getId() == null) {
            refund.setId(UUID.randomUUID());
        }
        ledger.put(refund.getId(), refund);
        return refund;
    }

    private BillingInvoice invoice(UUID hospitalId, String total, String paid) {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        BillingInvoice built = BillingInvoice.builder().hospital(hospital).patient(patient)
            .invoiceNumber("CHWF-2026-000013").totalAmount(new BigDecimal(total)).amountPaid(new BigDecimal(paid))
            .status(InvoiceStatus.PAID).build();
        built.setId(UUID.randomUUID());
        when(invoices.findLockedById(built.getId())).thenReturn(Optional.of(built));
        when(invoices.findById(built.getId())).thenReturn(Optional.of(built));
        return built;
    }

    private void actAs(UUID user) {
        when(roles.getCurrentUserId()).thenReturn(user);
    }

    private static InvoiceRefundRequestDTO cash(String amount, String reason) {
        return InvoiceRefundRequestDTO.builder().amount(amount == null ? null : new BigDecimal(amount))
            .method("CASH").reason(reason).build();
    }

    private static InvoiceRefundReasonRequestDTO because(String reason) {
        return InvoiceRefundReasonRequestDTO.builder().reason(reason).build();
    }

    private InvoiceRefundDTO requested(String amount) {
        actAs(BILLING_A);
        return service.request(invoice.getId(), cash(amount, "Trop-perçu du ticket modérateur"));
    }

    private InvoiceRefundDTO approved(String amount) {
        InvoiceRefundDTO asked = requested(amount);
        actAs(ADMIN_A);
        return service.approve(invoice.getId(), asked.getId());
    }

    // ───────────────────────── the D1900 scenario ─────────────────────────

    @Test
    @DisplayName("D1900: 500 F asked by the cashier, approved by the admin, paid: nothing left to refund")
    void theD1900ScenarioEndsWithNothingLeftToRefund() {
        assertThat(InvoiceBalance.refundDueOf(invoice)).isEqualByComparingTo("500");

        InvoiceRefundDTO asked = requested("500");
        assertThat(asked.getStatus()).isEqualTo("REQUESTED");
        assertThat(asked.isRequestedByMe()).isTrue();

        // The same person cannot approve it (a cashier has no approver role; a requesting admin is refused too).
        actAs(BILLING_A);
        UUID refundId = asked.getId();
        UUID invoiceId = invoice.getId();
        assertThatThrownBy(() -> service.approve(invoiceId, refundId))
            .isInstanceOf(ClientSafeAccessDeniedException.class);

        actAs(ADMIN_A);
        assertThat(service.approve(invoice.getId(), refundId).getStatus()).isEqualTo("APPROVED");

        actAs(BILLING_A);
        InvoiceRefundDTO paid = service.pay(invoice.getId(), refundId, null);

        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(paid.getPaidBy()).isEqualTo(BILLING_A);
        assertThat(invoice.getAmountRefunded()).isEqualByComparingTo("500");
        assertThat(InvoiceBalance.refundDueOf(invoice)).isEqualByComparingTo("0");
        assertThat(InvoiceBalance.dueOf(invoice)).isEqualByComparingTo("0");
        // The 2 000 F that came in is untouched: the refund is its own entry, not a rewritten payment.
        assertThat(invoice.getAmountPaid()).isEqualByComparingTo("2000");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("D-3: an approver who asked for the refund cannot approve or reject it")
    void theRequesterNeverDecides() {
        actAs(ADMIN_A);
        InvoiceRefundDTO asked = service.request(invoice.getId(), cash("500", "Trop-perçu"));
        UUID refundId = asked.getId();
        UUID invoiceId = invoice.getId();
        InvoiceRefundReasonRequestDTO no = because("Non");

        assertThatThrownBy(() -> service.approve(invoiceId, refundId))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Un autre responsable doit approuver");
        assertThatThrownBy(() -> service.reject(invoiceId, refundId, no))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("Un autre responsable doit approuver");
        assertThat(ledger.get(refundId).getStatus()).isEqualTo(InvoiceRefundStatus.REQUESTED);

        actAs(ACCOUNTANT_A);
        assertThat(service.approve(invoice.getId(), refundId).getDecidedBy()).isEqualTo(ACCOUNTANT_A);
    }

    // ───────────────────────── amounts and fields ─────────────────────────

    @Test
    @DisplayName("a request above what is left (open requests counted) is refused, naming the maximum")
    void aRequestAboveTheRefundableIsRefusedNamingTheMaximum() {
        requested("300");
        actAs(BILLING_A);
        UUID invoiceId = invoice.getId();
        InvoiceRefundRequestDTO tooMuch = cash("300", "Encore");

        assertThatThrownBy(() -> service.request(invoiceId, tooMuch))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal -> {
                assertThat(refusal.getField()).isEqualTo("amount");
                assertThat(refusal.getMessage()).contains("200 F CFA");
            });
    }

    @ParameterizedTest(name = "amount {0} is refused ({1})")
    @CsvSource({"0, billing.refund.amountPositive", "-5, billing.refund.amountPositive",
        "100.50, billing.payment.wholeAmountOnly"})
    void zeroNegativeAndDecimalAmountsAreRefused(String amount, String key) {
        actAs(BILLING_A);
        UUID invoiceId = invoice.getId();
        InvoiceRefundRequestDTO body = cash(amount, "Trop-perçu");
        assertThatThrownBy(() -> service.request(invoiceId, body))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal ->
                assertThat(refusal.getMessageKey()).isEqualTo(key));
    }

    @Test
    @DisplayName("an empty request names the amount, the method and the reason (Motif) at once")
    void anEmptyRequestNamesEveryMissingField() {
        actAs(BILLING_A);
        UUID invoiceId = invoice.getId();
        InvoiceRefundRequestDTO empty = new InvoiceRefundRequestDTO();
        assertThatThrownBy(() -> service.request(invoiceId, empty))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal -> {
                assertThat(refusal.getFieldErrors()).containsOnlyKeys("amount", "method", "reason");
                assertThat(refusal.getFieldErrors().get("reason")).startsWith("Motif");
            });
        assertThat(ledger).isEmpty();
    }

    @Test
    @DisplayName("nothing to refund: refused")
    void anInvoiceWithNothingDueTakesNoRefund() {
        BillingInvoice settled = invoice(HOSPITAL_A, "1500", "1500");
        actAs(BILLING_A);
        UUID invoiceId = settled.getId();
        InvoiceRefundRequestDTO body = cash("100", "Erreur");
        assertThatThrownBy(() -> service.request(invoiceId, body))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal ->
                assertThat(refusal.getMessageKey()).isEqualTo("billing.refund.nothingDue"));
    }

    @Test
    @DisplayName("PR 8 review: mobile money is asked for without its reference; the payout requires operator and reference")
    void mobileMoneyNeedsAnOperatorAndAnUnusedReferenceAtPayout() {
        actAs(BILLING_A);
        UUID invoiceId = invoice.getId();
        // Asked for without the operator's reference: it does not exist until the money is sent.
        InvoiceRefundDTO asked = service.request(invoiceId, InvoiceRefundRequestDTO.builder()
            .amount(new BigDecimal("500")).method("MOBILE_MONEY").reason("Trop-perçu").build());
        actAs(ADMIN_A);
        UUID refundId = asked.getId();
        service.approve(invoiceId, refundId);
        actAs(BILLING_A);

        assertThatThrownBy(() -> service.pay(invoiceId, refundId, null))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal ->
                assertThat(refusal.getMessageKey()).isEqualTo("billing.payment.operatorRequired"));
        InvoiceRefundPayRequestDTO noReference = InvoiceRefundPayRequestDTO.builder()
            .mobileMoneyOperator(MobileMoneyOperator.ORANGE_MONEY).build();
        assertThatThrownBy(() -> service.pay(invoiceId, refundId, noReference))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal ->
                assertThat(refusal.getField()).isEqualTo("reference"));
        InvoiceRefundPayRequestDTO card = InvoiceRefundPayRequestDTO.builder()
            .mobileMoneyOperator(MobileMoneyOperator.ORANGE_MONEY).reference("4111 1111 1111 1111").build();
        assertThatThrownBy(() -> service.pay(invoiceId, refundId, card))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal ->
                assertThat(refusal.getMessageKey()).isEqualTo(com.example.hms.validation.NoCardNumber.MESSAGE_KEY));
        doThrow(new FieldBusinessException("reference", PaymentReferenceRegistry.DUPLICATE_KEY))
            .when(registry).requireUnused(HOSPITAL_A, "OM-PAID-1", "reference");
        InvoiceRefundPayRequestDTO reused = InvoiceRefundPayRequestDTO.builder()
            .mobileMoneyOperator(MobileMoneyOperator.ORANGE_MONEY).reference("OM-PAID-1").build();
        assertThatThrownBy(() -> service.pay(invoiceId, refundId, reused))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal ->
                assertThat(refusal.getMessageKey()).isEqualTo(PaymentReferenceRegistry.DUPLICATE_KEY));
        assertThat(ledger.get(refundId).getStatus()).isEqualTo(InvoiceRefundStatus.APPROVED);
        assertThat(invoice.getAmountRefunded()).isEqualByComparingTo("0");

        InvoiceRefundDTO paid = service.pay(invoiceId, refundId, InvoiceRefundPayRequestDTO.builder()
            .mobileMoneyOperator(MobileMoneyOperator.ORANGE_MONEY).reference("OM-OUT-9").build());
        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(paid.getMobileMoneyOperator()).isEqualTo(MobileMoneyOperator.ORANGE_MONEY.name());
        assertThat(paid.getReference()).isEqualTo("OM-OUT-9");
        verify(registry).requireUnused(HOSPITAL_A, "OM-OUT-9", "reference");
    }

    @Test
    @DisplayName("PR 8 review: a bank transfer refund needs its reference at payout; a cash one needs none")
    void aBankTransferNeedsItsReferenceAtPayout() {
        actAs(BILLING_A);
        UUID invoiceId = invoice.getId();
        InvoiceRefundDTO asked = service.request(invoiceId, InvoiceRefundRequestDTO.builder()
            .amount(new BigDecimal("500")).method("BANK_TRANSFER").reason("Trop-perçu").build());
        actAs(ADMIN_A);
        UUID refundId = asked.getId();
        service.approve(invoiceId, refundId);
        actAs(BILLING_A);
        assertThatThrownBy(() -> service.pay(invoiceId, refundId, null))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal ->
                assertThat(refusal.getField()).isEqualTo("reference"));
        assertThat(service.pay(invoiceId, refundId, InvoiceRefundPayRequestDTO.builder().reference("VIR-77").build())
            .getStatus()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("an insurer's share is never handed back at the till")
    void insuranceIsNotARefundMethod() {
        actAs(BILLING_A);
        UUID invoiceId = invoice.getId();
        InvoiceRefundRequestDTO body = InvoiceRefundRequestDTO.builder().amount(new BigDecimal("500"))
            .method("INSURANCE").reason("x").build();
        assertThatThrownBy(() -> service.request(invoiceId, body)).isInstanceOf(FieldBusinessException.class);
    }

    @Test
    @DisplayName("no card number in a reason, a reference or a decision; the refusal never repeats it")
    void aCardNumberIsRefusedInEveryRefundText() {
        actAs(BILLING_A);
        UUID invoiceId = invoice.getId();
        InvoiceRefundRequestDTO inReason = cash("500", "Rendu sur la carte 4111 1111 1111 1111");
        assertThatThrownBy(() -> service.request(invoiceId, inReason))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal -> {
                assertThat(refusal.getField()).isEqualTo("reason");
                assertThat(refusal.getMessageKey()).isEqualTo(com.example.hms.validation.NoCardNumber.MESSAGE_KEY);
                assertThat(refusal.getMessage()).doesNotContain("4111").contains("carte");
            });
        InvoiceRefundRequestDTO inReference = InvoiceRefundRequestDTO.builder().amount(new BigDecimal("500"))
            .method("BANK_TRANSFER").reference("4111111111111111").reason("Trop-perçu").build();
        assertThatThrownBy(() -> service.request(invoiceId, inReference))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal ->
                assertThat(refusal.getField()).isEqualTo("reference"));

        InvoiceRefundDTO asked = requested("500");
        actAs(ADMIN_A);
        UUID refundId = asked.getId();
        InvoiceRefundReasonRequestDTO card = because("Carte 5555555555554444");
        assertThatThrownBy(() -> service.reject(invoiceId, refundId, card))
            .isInstanceOf(FieldBusinessException.class);
        assertThat(ledger.get(refundId).getStatus()).isEqualTo(InvoiceRefundStatus.REQUESTED);
    }

    // ───────────────────────── decisions and the state table ─────────────────────────

    @Test
    @DisplayName("rejecting needs a reason, and a second decision is refused")
    void rejectingNeedsAReasonAndASecondDecisionIsRefused() {
        InvoiceRefundDTO asked = requested("500");
        actAs(ADMIN_A);
        UUID invoiceId = invoice.getId();
        UUID refundId = asked.getId();
        InvoiceRefundReasonRequestDTO blank = because("  ");
        assertThatThrownBy(() -> service.reject(invoiceId, refundId, blank))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal ->
                assertThat(refusal.getFieldErrors()).containsOnlyKeys("reason"));

        InvoiceRefundDTO rejected = service.reject(invoiceId, refundId, because("Déjà rendu en espèces hier"));
        assertThat(rejected.getStatus()).isEqualTo("REJECTED");
        assertThat(rejected.getDecisionReason()).isEqualTo("Déjà rendu en espèces hier");

        actAs(ACCOUNTANT_A);
        assertThatThrownBy(() -> service.approve(invoiceId, refundId)).isInstanceOf(ConflictException.class);
        InvoiceRefundReasonRequestDTO again = because("Encore");
        assertThatThrownBy(() -> service.reject(invoiceId, refundId, again))
            .isInstanceOf(ConflictException.class);
    }

    /**
     * The state table: from each status, each step either moves the refund or is refused with a named reason (409),
     * and a refused step changes nothing.
     */
    @ParameterizedTest(name = "{0} --{1}--> {2}")
    @CsvSource({
        "REQUESTED, approve, APPROVED",
        "REQUESTED, reject, REJECTED",
        "REQUESTED, pay, billing.refund.notApproved",
        "REQUESTED, cancel, CANCELLED",
        "APPROVED, approve, billing.refund.notRequested",
        "APPROVED, reject, billing.refund.notRequested",
        "APPROVED, pay, PAID",
        "APPROVED, cancel, CANCELLED",
        "REJECTED, approve, billing.refund.notRequested",
        "REJECTED, reject, billing.refund.notRequested",
        "REJECTED, pay, billing.refund.notApproved",
        "REJECTED, cancel, billing.refund.notCancellable",
        "PAID, approve, billing.refund.notRequested",
        "PAID, reject, billing.refund.notRequested",
        "PAID, pay, billing.refund.notApproved",
        "PAID, cancel, billing.refund.notCancellable",
        "CANCELLED, approve, billing.refund.notRequested",
        "CANCELLED, reject, billing.refund.notRequested",
        "CANCELLED, pay, billing.refund.notApproved",
        "CANCELLED, cancel, billing.refund.notCancellable",
    })
    void theStateTable(InvoiceRefundStatus from, String step, String expected) {
        InvoiceRefund refund = store(InvoiceRefund.builder().invoice(invoice).hospitalId(HOSPITAL_A)
            .amount(new BigDecimal("500")).method(PaymentMethod.CASH).reason("Trop-perçu").status(from)
            .requestedBy(BILLING_A).requestedAt(java.time.LocalDateTime.now(clock)).build());
        // The cashier asked; the admin decides; the cashier pays and cancels.
        actAs("approve".equals(step) || "reject".equals(step) ? ADMIN_A : BILLING_A);
        UUID invoiceId = invoice.getId();
        UUID refundId = refund.getId();
        InvoiceRefundReasonRequestDTO rejection = because("Non justifié");
        InvoiceRefundReasonRequestDTO withdrawal = because("Patient parti");
        Runnable call = switch (step) {
            case "approve" -> () -> service.approve(invoiceId, refundId);
            case "reject" -> () -> service.reject(invoiceId, refundId, rejection);
            case "pay" -> () -> service.pay(invoiceId, refundId, null);
            default -> () -> service.cancel(invoiceId, refundId, withdrawal);
        };
        if (expected.startsWith("billing.")) {
            String text = MessageUtil.resolve(expected);
            assertThatThrownBy(call::run).isInstanceOf(ConflictException.class).hasMessage(text);
            assertThat(refund.getStatus()).isEqualTo(from);
        } else {
            call.run();
            assertThat(refund.getStatus()).isEqualTo(InvoiceRefundStatus.valueOf(expected));
        }
    }

    @Test
    @DisplayName("a paid refund cannot be cancelled, and is paid once")
    void aPaidRefundCannotBeCancelledNorPaidTwice() {
        InvoiceRefundDTO approved = approved("500");
        actAs(BILLING_A);
        UUID invoiceId = invoice.getId();
        UUID refundId = approved.getId();
        service.pay(invoiceId, refundId, null);
        assertThatThrownBy(() -> service.pay(invoiceId, refundId, null)).isInstanceOf(ConflictException.class);
        InvoiceRefundReasonRequestDTO error = because("Erreur");
        assertThatThrownBy(() -> service.cancel(invoiceId, refundId, error))
            .isInstanceOf(ConflictException.class);
        assertThat(invoice.getAmountRefunded()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("a refund that lost the race to a concurrent payout moves no money")
    void aLostRaceMovesNoMoney() {
        InvoiceRefundDTO approved = approved("500");
        when(refunds.markPaid(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);
        actAs(BILLING_A);
        UUID invoiceId = invoice.getId();
        UUID refundId = approved.getId();
        assertThatThrownBy(() -> service.pay(invoiceId, refundId, null)).isInstanceOf(ConflictException.class);
        assertThat(invoice.getAmountRefunded()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("an approved refund that no longer fits the balance is not paid")
    void anApprovedRefundAboveTheBalanceNowIsNotPaid() {
        InvoiceRefundDTO approved = approved("500");
        invoice.setTotalAmount(new BigDecimal("1800")); // a line added since
        actAs(BILLING_A);
        UUID invoiceId = invoice.getId();
        UUID refundId = approved.getId();
        assertThatThrownBy(() -> service.pay(invoiceId, refundId, null))
            .isInstanceOf(ConflictException.class).hasMessageContaining("200 F CFA");
        assertThat(invoice.getAmountRefunded()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("only the requester cancels a request; an approver may also withdraw an approved one")
    void whoMayCancel() {
        InvoiceRefundDTO asked = requested("200");
        actAs(RECEPTION_A);
        UUID invoiceId = invoice.getId();
        UUID askedId = asked.getId();
        InvoiceRefundReasonRequestDTO error = because("Erreur");
        InvoiceRefundReasonRequestDTO blank = because(" ");
        assertThatThrownBy(() -> service.cancel(invoiceId, askedId, error))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        actAs(BILLING_A);
        assertThatThrownBy(() -> service.cancel(invoiceId, askedId, blank))
            .isInstanceOfSatisfying(FieldBusinessException.class, refusal ->
                assertThat(refusal.getFieldErrors()).containsOnlyKeys("reason"));
        assertThat(service.cancel(invoiceId, askedId, because("Saisie en double")).getStatus())
            .isEqualTo("CANCELLED");

        InvoiceRefundDTO approved = approved("300");
        actAs(ACCOUNTANT_A);
        assertThat(service.cancel(invoiceId, approved.getId(), because("Patient injoignable")).getStatus())
            .isEqualTo("CANCELLED");
    }

    // ───────────────────────── roles and tenancy ─────────────────────────

    @Test
    @DisplayName("a role the assignment does not hold is refused before anything is read (super-admin included)")
    void rolesAreCheckedOnTheAssignmentBeforeAnyLookup() {
        UUID superAdmin = UUID.randomUUID();
        actAs(superAdmin);
        UUID invoiceId = invoice.getId();
        InvoiceRefundRequestDTO body = cash("500", "x");
        assertThatThrownBy(() -> service.request(invoiceId, body)).isInstanceOf(ClientSafeAccessDeniedException.class);
        UUID anyRefund = UUID.randomUUID();
        assertThatThrownBy(() -> service.pay(invoiceId, anyRefund, null))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        actAs(RECEPTION_A);
        assertThatThrownBy(() -> service.approve(invoiceId, anyRefund))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        verify(invoices, never()).findLockedById(any());
    }

    @Test
    @DisplayName("hospital B gets 404 on every refund route of A's invoice")
    void anotherHospitalsInvoiceIsNotFound() {
        InvoiceRefundDTO asked = requested("500");
        UUID refundId = asked.getId();
        UUID invoiceId = invoice.getId();
        UUID billingB = UUID.randomUUID();
        when(scope.requirePinned()).thenReturn(HOSPITAL_B);
        when(roles.requireActiveHospitalId()).thenReturn(HOSPITAL_B);
        when(roles.canRequestRefund(billingB, HOSPITAL_B)).thenReturn(true);
        when(roles.canApproveRefund(billingB, HOSPITAL_B)).thenReturn(true);
        when(roles.canPayRefund(billingB, HOSPITAL_B)).thenReturn(true);
        actAs(billingB);

        InvoiceRefundRequestDTO body = cash("500", "x");
        InvoiceRefundReasonRequestDTO reason = because("x");
        List<Runnable> routes = new ArrayList<>(List.of(
            () -> service.request(invoiceId, body),
            () -> service.approve(invoiceId, refundId),
            () -> service.reject(invoiceId, refundId, reason),
            () -> service.pay(invoiceId, refundId, null),
            () -> service.cancel(invoiceId, refundId, reason),
            () -> service.list(invoiceId),
            () -> service.receipt(invoiceId, refundId, Locale.FRENCH)));
        for (Runnable route : routes) {
            assertThatThrownBy(route::run).isInstanceOf(ResourceNotFoundException.class);
        }
        assertThat(ledger.get(refundId).getStatus()).isEqualTo(InvoiceRefundStatus.REQUESTED);
    }

    @Test
    @DisplayName("a retried request with the same key returns the refund already asked for")
    void aRetriedRequestIsIdempotent() {
        actAs(BILLING_A);
        UUID key = UUID.randomUUID();
        InvoiceRefundRequestDTO body = cash("500", "Trop-perçu");
        body.setClientRequestId(key);
        InvoiceRefundDTO first = service.request(invoice.getId(), body);
        InvoiceRefundDTO again = service.request(invoice.getId(), body);
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(ledger).hasSize(1);

        BillingInvoice other = invoice(HOSPITAL_A, "1000", "1500");
        UUID otherId = other.getId();
        assertThatThrownBy(() -> service.request(otherId, body)).isInstanceOf(ConflictException.class);
    }

    // ───────────────────────── audit ─────────────────────────

    @Test
    @DisplayName("each step is audited with ids only; REFUND_ISSUED only when money is paid out")
    void eachStepIsAuditedAndRefundIssuedOnlyOnPayout() {
        InvoiceRefundDTO approved = approved("500");
        actAs(BILLING_A);
        service.pay(invoice.getId(), approved.getId(), null);

        ArgumentCaptor<AuditEventRequestDTO> logged = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit, atLeastOnce()).logEvent(logged.capture());
        assertThat(logged.getAllValues()).extracting(AuditEventRequestDTO::getEventType)
            .containsExactly(AuditEventType.REFUND_REQUESTED, AuditEventType.REFUND_APPROVED,
                AuditEventType.REFUND_ISSUED);
        assertThat(logged.getAllValues()).allSatisfy(event -> {
            assertThat(event.getEventDescription()).doesNotContain("500").doesNotContain("Trop-perçu");
            assertThat(event.getResourceId()).isEqualTo(invoice.getId().toString());
        });
    }

    @Test
    @DisplayName("the receipt exists only for a refund handed over")
    void theReceiptIsOnlyForAPaidRefund() {
        InvoiceRefundDTO asked = requested("500");
        actAs(BILLING_A);
        UUID invoiceId = invoice.getId();
        UUID refundId = asked.getId();
        assertThatThrownBy(() -> service.receipt(invoiceId, refundId, Locale.FRENCH))
            .isInstanceOf(ConflictException.class);

        actAs(ADMIN_A);
        service.approve(invoiceId, refundId);
        actAs(BILLING_A);
        service.pay(invoiceId, refundId, null);
        when(pdf.generateRefundReceiptPdf(any(), any(), anyString(), any(), any())).thenReturn(new byte[] {1});
        assertThat(service.receipt(invoiceId, refundId, Locale.FRENCH).suggestedFilename())
            .isEqualTo("CHWF-2026-000013-RB1");
    }

    @Test
    @DisplayName("PR 8 review: a receipt number is given once at payout, in payout order, and never renumbered")
    void receiptNumbersAreAssignedAtPayoutAndNeverRenumbered() {
        // Two refunds of one invoice: the one asked for SECOND is paid FIRST, and gets -RB1.
        InvoiceRefundDTO first = requested("200");
        InvoiceRefundDTO second = requested("300");
        UUID invoiceId = invoice.getId();
        actAs(ADMIN_A);
        service.approve(invoiceId, first.getId());
        service.approve(invoiceId, second.getId());
        actAs(BILLING_A);
        assertThat(service.pay(invoiceId, second.getId(), null).getReceiptNumber()).isEqualTo("CHWF-2026-000013-RB1");
        assertThat(service.pay(invoiceId, first.getId(), null).getReceiptNumber()).isEqualTo("CHWF-2026-000013-RB2");
        // The receipt prints the stored number, whatever the creation order says.
        when(pdf.generateRefundReceiptPdf(any(), any(), anyString(), any(), any())).thenReturn(new byte[] {1});
        assertThat(service.receipt(invoiceId, second.getId(), Locale.FRENCH).suggestedFilename())
            .isEqualTo("CHWF-2026-000013-RB1");
        assertThat(service.receipt(invoiceId, first.getId(), Locale.FRENCH).suggestedFilename())
            .isEqualTo("CHWF-2026-000013-RB2");
    }

    @Test
    @DisplayName("PR 8 review: a key replayed with another amount or method is refused, never answered silently")
    void aReplayWithAnotherAmountIsRefused() {
        actAs(BILLING_A);
        UUID key = UUID.randomUUID();
        InvoiceRefundRequestDTO body = cash("300", "Trop-perçu");
        body.setClientRequestId(key);
        service.request(invoice.getId(), body);
        InvoiceRefundRequestDTO changed = cash("400", "Trop-perçu");
        changed.setClientRequestId(key);
        UUID invoiceId = invoice.getId();
        assertThatThrownBy(() -> service.request(invoiceId, changed)).isInstanceOf(ConflictException.class);
        assertThat(ledger).hasSize(1);
    }

    /**
     * PR 8 review (HIGH): a support session impersonating a cashier, an accountant or an admin moves no money:
     * every refund write is refused with an audit row, before anything is read.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0} is refused while impersonating")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"request", "approve", "reject", "pay", "cancel"})
    void everyRefundWriteIsRefusedWhileImpersonating(String step) {
        InvoiceRefundDTO asked = requested("500");
        UUID invoiceId = invoice.getId();
        UUID refundId = asked.getId();
        org.mockito.Mockito.clearInvocations(audit, invoices);
        com.example.hms.security.context.ImpersonationContextHolder.set(
            com.example.hms.security.context.ImpersonationContext.of(UUID.randomUUID(), "support.agent"));
        try {
            actAs(ADMIN_A);
            InvoiceRefundRequestDTO body = cash("100", "x");
            InvoiceRefundReasonRequestDTO reason = because("x");
            Runnable call = switch (step) {
                case "request" -> () -> service.request(invoiceId, body);
                case "approve" -> () -> service.approve(invoiceId, refundId);
                case "reject" -> () -> service.reject(invoiceId, refundId, reason);
                case "pay" -> () -> service.pay(invoiceId, refundId, null);
                default -> () -> service.cancel(invoiceId, refundId, reason);
            };
            assertThatThrownBy(call::run).isInstanceOf(ClientSafeAccessDeniedException.class);
        } finally {
            com.example.hms.security.context.ImpersonationContextHolder.clear();
        }
        verify(invoices, never()).findLockedById(any());
        verify(audit).logEvent(org.mockito.ArgumentMatchers.argThat(e ->
            e.getEventType() == AuditEventType.SECURITY_ALERT_TRIGGERED
                && e.getEventDescription().contains("(money)")));
        assertThat(ledger.get(refundId).getStatus()).isEqualTo(InvoiceRefundStatus.REQUESTED);
    }
}
