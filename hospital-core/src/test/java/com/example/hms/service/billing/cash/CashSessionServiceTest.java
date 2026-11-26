package com.example.hms.service.billing.cash;

import com.example.hms.enums.CashSessionStatus;
import com.example.hms.enums.CashTill;
import com.example.hms.enums.InvoiceRefundStatus;
import com.example.hms.enums.MobileMoneyOperator;
import com.example.hms.enums.PaymentMethod;
import com.example.hms.enums.PaymentStatus;
import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.enums.PharmacySaleStatus;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.CashSession;
import com.example.hms.model.CashSessionCount;
import com.example.hms.model.InvoiceRefund;
import com.example.hms.model.PaymentTransaction;
import com.example.hms.model.pharmacy.PharmacySale;
import com.example.hms.payload.dto.billing.CashSessionCloseRequestDTO;
import com.example.hms.payload.dto.billing.CashSessionDTO;
import com.example.hms.repository.CashSessionCountRepository;
import com.example.hms.repository.CashSessionRepository;
import com.example.hms.repository.InvoiceRefundRepository;
import com.example.hms.repository.PaymentTransactionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.PharmacyPaymentRepository;
import com.example.hms.repository.pharmacy.PharmacySaleRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.billing.TariffResolver;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 10 (QA r65 D2325, D2714, D2672): the expected amount per method (money in - money out), the
 * cash to count, the counted close with its explanation rule, the lock, and the supervisor's four-eyes reopen.
 * Plan acceptance: 1 000 cash and 850 Orange Money in, a 500 F refund out: expected cash 500, Orange 850.
 */
@DisplayName("Cash sessions: expected, counted close, lock and reopen")
class CashSessionServiceTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID CASHIER = UUID.randomUUID();
    private static final UUID ACCOUNTANT = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC);

    private final CashSessionRepository sessionRepo = mock(CashSessionRepository.class);
    private final CashSessionCountRepository countRepo = mock(CashSessionCountRepository.class);
    private final CashSessionWrites writes = mock(CashSessionWrites.class);
    private final PaymentTransactionRepository payments = mock(PaymentTransactionRepository.class);
    private final InvoiceRefundRepository refunds = mock(InvoiceRefundRepository.class);
    private final PharmacyPaymentRepository pharmacyPayments = mock(PharmacyPaymentRepository.class);
    private final PharmacySaleRepository sales = mock(PharmacySaleRepository.class);
    private final com.example.hms.repository.pharmacy.PharmacyRefundRepository pharmacyRefunds =
        mock(com.example.hms.repository.pharmacy.PharmacyRefundRepository.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);
    private final TariffResolver days = mock(TariffResolver.class);
    private final List<CashSessionCount> savedCounts = new ArrayList<>();

    private CashSessionService service;
    private CashSession session;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        UserRepository users = mock(UserRepository.class);
        when(users.findAllById(any())).thenReturn(List.of());
        service = new CashSessionService(sessionRepo, countRepo, writes, payments, refunds, pharmacyPayments, sales,
            pharmacyRefunds, users, roles, scope, days, mock(AuditEventLogService.class), CLOCK);
        when(scope.requirePinned()).thenReturn(HOSPITAL);
        when(days.today()).thenReturn(DAY);
        when(roles.canSuperviseTills(ACCOUNTANT, HOSPITAL)).thenReturn(true);
        when(roles.canHoldTill(CASHIER, HOSPITAL)).thenReturn(true);
        session = CashSession.builder().hospitalId(HOSPITAL).cashierUserId(CASHIER).till(CashTill.BILLING)
            .businessDate(DAY).status(CashSessionStatus.OPEN).openedAt(LocalDateTime.of(2026, 11, 20, 8, 0)).build();
        session.setId(UUID.randomUUID());
        when(sessionRepo.findLockedByIdAndHospitalId(session.getId(), HOSPITAL)).thenReturn(Optional.of(session));
        when(sessionRepo.findByIdAndHospitalId(session.getId(), HOSPITAL)).thenReturn(Optional.of(session));
        when(countRepo.saveAll(anyList())).thenAnswer(call -> {
            savedCounts.addAll(call.getArgument(0));
            return call.getArgument(0);
        });
        when(countRepo.findBySessionIdOrderByCloseRoundAscMethodAsc(session.getId())).thenReturn(savedCounts);

        // 1 000 cash and 850 Orange Money in; a 500 F cash refund handed back; a 300 F counter sale in cash.
        BillingInvoice invoice = BillingInvoice.builder().invoiceNumber("TWP-2026-000030").build();
        PaymentTransaction cash = PaymentTransaction.builder().invoice(invoice).amount(new BigDecimal("1000"))
            .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.CONFIRMED).build();
        PaymentTransaction orange = PaymentTransaction.builder().invoice(invoice).amount(new BigDecimal("850"))
            .paymentMethod(PaymentMethod.MOBILE_MONEY).mobileMoneyOperator(MobileMoneyOperator.ORANGE_MONEY)
            .referenceNumber("OM-1").status(PaymentStatus.CONFIRMED).build();
        // A payment reversed through an approved refund stays money IN; the refund is the money out.
        PaymentTransaction reversed = PaymentTransaction.builder().invoice(invoice).amount(new BigDecimal("200"))
            .paymentMethod(PaymentMethod.CASH).status(PaymentStatus.REVERSED).build();
        when(payments.findByCashSessionIdOrderByCreatedAtAsc(session.getId()))
            .thenReturn(List.of(cash, orange, reversed));
        InvoiceRefund refund = InvoiceRefund.builder().invoice(invoice).amount(new BigDecimal("500"))
            .method(PaymentMethod.CASH).status(InvoiceRefundStatus.PAID).paidAt(LocalDateTime.of(2026, 11, 20, 15, 0))
            .build();
        InvoiceRefund reversal = InvoiceRefund.builder().invoice(invoice).amount(new BigDecimal("200"))
            .method(PaymentMethod.CASH).status(InvoiceRefundStatus.PAID).reversedPaymentId(UUID.randomUUID())
            .paidAt(LocalDateTime.of(2026, 11, 20, 16, 0)).build();
        when(refunds.findByCashSessionIdOrderByPaidAtAsc(session.getId())).thenReturn(List.of(refund, reversal));
        PharmacySale sale = PharmacySale.builder().totalAmount(new BigDecimal("300"))
            .paymentMethod(PharmacyPaymentMethod.CASH).status(PharmacySaleStatus.COMPLETED)
            .saleDate(LocalDateTime.of(2026, 11, 20, 11, 0)).build();
        when(sales.findByCashSessionIdOrderByCreatedAtAsc(session.getId())).thenReturn(List.of(sale));
        when(pharmacyPayments.findByCashSessionIdOrderByCreatedAtAsc(session.getId())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    private void actAs(UUID user) {
        when(roles.getCurrentUserId()).thenReturn(user);
    }

    private static CashSessionCloseRequestDTO counted(String cash, String orange, String explanation) {
        List<CashSessionCloseRequestDTO.CountedLine> lines = new ArrayList<>();
        lines.add(new CashSessionCloseRequestDTO.CountedLine("CASH", null, cash == null ? null : new BigDecimal(cash),
            explanation));
        lines.add(new CashSessionCloseRequestDTO.CountedLine("MOBILE_MONEY", MobileMoneyOperator.ORANGE_MONEY,
            orange == null ? null : new BigDecimal(orange), null));
        return new CashSessionCloseRequestDTO(lines);
    }

    @Test
    @DisplayName("QA D3410 (r70): a payout comes from the payer open till, never one opened by the payout itself")
    void d3410_aPayoutNeedsATillThePayerAlreadyHolds() {
        when(sessionRepo.findLockedForDay(HOSPITAL, CASHIER, CashTill.PHARMACY, DAY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.stampPayout(CashTill.PHARMACY, HOSPITAL, CASHIER, "CASH",
            new BigDecimal("200")))
            .isInstanceOf(com.example.hms.exception.ConflictException.class)
            .hasMessageContaining("caisse");
        verify(writes, never()).insertIfAbsent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("QA D3410 (r70): a cash payout above the cash in the till is refused; within it, it is stamped")
    void d3410_aCashPayoutNeverTakesTheTillNegative() {
        when(sessionRepo.findLockedForDay(HOSPITAL, CASHIER, CashTill.BILLING, DAY)).thenReturn(Optional.of(session));

        // The till holds 800 F cash (see setUp).
        assertThatThrownBy(() -> service.stampPayout(CashTill.BILLING, HOSPITAL, CASHIER, "CASH",
            new BigDecimal("801")))
            .isInstanceOf(com.example.hms.exception.ConflictException.class)
            .hasMessageContaining("801").hasMessageContaining("800");
        assertThat(service.stampPayout(CashTill.BILLING, HOSPITAL, CASHIER, "CASH", new BigDecimal("800")))
            .isEqualTo(session.getId());
        // A mobile-money payout is not bounded by the cash drawer.
        assertThat(service.stampPayout(CashTill.BILLING, HOSPITAL, CASHIER, "MOBILE_MONEY", new BigDecimal("5000")))
            .isEqualTo(session.getId());
    }

    @Test
    @DisplayName("QA D2714: expected per method is in - out; the cash to count is the cash line alone")
    void expectedPerMethodAndCashToCount() {
        actAs(CASHIER);
        CashSessionDTO dto = service.get(session.getId());

        // Cash: 1 000 + 200 (reversed later, still came in) + 300 sale - 500 refund - 200 reversal = 800.
        assertThat(dto.getCashToCount()).isEqualByComparingTo("800");
        assertThat(dto.getMethods()).anySatisfy(line -> {
            assertThat(line.getMethod()).isEqualTo("MOBILE_MONEY");
            assertThat(line.getOperator()).isEqualTo("ORANGE_MONEY");
            assertThat(line.getExpected()).isEqualByComparingTo("850");
        });
        assertThat(dto.getTotalIn()).isEqualByComparingTo("2350");
        assertThat(dto.getTotalOut()).isEqualByComparingTo("700");
        assertThat(dto.getTotalExpected()).isEqualByComparingTo("1650");
        assertThat(dto.getMovements()).extracting(CashSessionDTO.Movement::getKind)
            .contains("PAYMENT", "COUNTER_SALE", "REFUND", "REVERSAL");
    }

    @Test
    @DisplayName("QA D2325: counted as expected closes with no difference; the session is then CLOSED")
    void closeWithNoDifference() {
        actAs(CASHIER);
        CashSessionDTO closed = service.close(session.getId(), counted("800", "850", null));

        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getCloseRound()).isEqualTo(1);
        assertThat(closed.getTotalDifference()).isEqualByComparingTo("0");
        assertThat(savedCounts).hasSize(2).allSatisfy(c -> assertThat(c.getCloseRound()).isEqualTo(1));
    }

    @Test
    @DisplayName("QA D2325: a difference needs an explanation; with it, the -100 F gap is kept")
    void differenceNeedsAnExplanation() {
        actAs(CASHIER);
        UUID id = session.getId();
        CashSessionCloseRequestDTO body1 = counted("700", "850", null);
        assertThatThrownBy(() -> service.close(id, body1))
            .isInstanceOf(FieldBusinessException.class);
        assertThat(session.getStatus()).isEqualTo(CashSessionStatus.OPEN);

        CashSessionDTO closed = service.close(id, counted("700", "850", "Billet de 100 manquant"));
        assertThat(closed.getTotalDifference()).isEqualByComparingTo("-100");
        assertThat(savedCounts).anySatisfy(c -> {
            assertThat(c.getMethod()).isEqualTo("CASH");
            assertThat(c.getDifference()).isEqualByComparingTo("-100");
            assertThat(c.getExplanation()).isEqualTo("Billet de 100 manquant");
        });
    }

    @Test
    @DisplayName("every expected method must be counted, in whole francs")
    void everyMethodCounted() {
        actAs(CASHIER);
        UUID id = session.getId();
        CashSessionCloseRequestDTO body2 = counted("800", null, null);
        assertThatThrownBy(() -> service.close(id, body2))
            .isInstanceOf(FieldBusinessException.class);
        CashSessionCloseRequestDTO body3 = counted("800.50", "850", "x");
        assertThatThrownBy(() -> service.close(id, body3))
            .isInstanceOf(FieldBusinessException.class);
        verify(countRepo, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("only the session's cashier closes it; another cashier's session answers as missing")
    void onlyTheCashierCloses() {
        UUID id = session.getId();
        actAs(ACCOUNTANT);
        CashSessionCloseRequestDTO body4 = counted("800", "850", null);
        assertThatThrownBy(() -> service.close(id, body4))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        actAs(UUID.randomUUID());
        CashSessionCloseRequestDTO body5 = counted("800", "850", null);
        assertThatThrownBy(() -> service.close(id, body5))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("lock: a closed session refuses any new movement with the « caisse clôturée » message")
    void closedSessionRefusesMovements() {
        session.setStatus(CashSessionStatus.CLOSED);
        when(sessionRepo.findLockedForDay(HOSPITAL, CASHIER, CashTill.BILLING, DAY)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.stamp(CashTill.BILLING, HOSPITAL, CASHIER))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("clôturée");
    }

    @Test
    @DisplayName("stamp: the first movement of the day opens the session, then every movement names it")
    void stampOpensTheSession() {
        when(sessionRepo.findLockedForDay(HOSPITAL, CASHIER, CashTill.PHARMACY, DAY))
            .thenReturn(Optional.empty(), Optional.of(session));
        session.setTill(CashTill.PHARMACY);

        assertThat(service.stamp(CashTill.PHARMACY, HOSPITAL, CASHIER)).isEqualTo(session.getId());
        verify(writes).insertIfAbsent(HOSPITAL, CASHIER, CashTill.PHARMACY, DAY);
    }

    @Test
    @DisplayName("reopen: a supervisor (not the cashier) with a reason; the earlier counts are kept")
    void reopenByAnotherSupervisor() {
        actAs(CASHIER);
        service.close(session.getId(), counted("800", "850", null));
        UUID id = session.getId();
        when(roles.canSuperviseTills(CASHIER, HOSPITAL)).thenReturn(true);
        assertThatThrownBy(() -> service.reopen(id, "Erreur")).isInstanceOf(ConflictException.class);

        actAs(ACCOUNTANT);
        assertThatThrownBy(() -> service.reopen(id, " ")).isInstanceOf(FieldBusinessException.class);
        CashSessionDTO reopened = service.reopen(id, "Paiement oublié");
        assertThat(reopened.getStatus()).isEqualTo("OPEN");
        assertThat(reopened.getReopenReason()).isEqualTo("Paiement oublié");
        assertThat(reopened.getCounts()).hasSize(2);

        actAs(CASHIER);
        service.close(id, counted("800", "850", null));
        assertThat(session.getCloseRound()).isEqualTo(2);
        assertThat(savedCounts).hasSize(4);
    }

    @Test
    @DisplayName("the supervisor list and the day report are for a hospital admin or an accountant")
    void supervisorsOnly() {
        actAs(CASHIER);
        assertThatThrownBy(() -> service.list(DAY, null)).isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> service.dayReport(DAY)).isInstanceOf(ClientSafeAccessDeniedException.class);
        actAs(ACCOUNTANT);
        when(sessionRepo.findByHospitalIdAndBusinessDateOrderByTillAscOpenedAtAsc(HOSPITAL, DAY))
            .thenReturn(List.of(session));
        assertThat(service.list(DAY, null)).hasSize(1);
        assertThat(service.list(DAY, CashTill.PHARMACY)).isEmpty();
    }

    @Test
    @DisplayName("review: a supervisor marks another cashier's closed till reviewed, with a note; never an open one")
    void reviewAClosedTill() {
        UUID id = session.getId();
        actAs(ACCOUNTANT);
        assertThatThrownBy(() -> service.review(id, "ok")).isInstanceOf(ConflictException.class);
        actAs(CASHIER);
        service.close(id, counted("800", "850", null));
        assertThatThrownBy(() -> service.review(id, "ok")).isInstanceOf(ClientSafeAccessDeniedException.class);
        actAs(ACCOUNTANT);
        CashSessionDTO reviewed = service.review(id, " Vérifiée avec le relevé Orange ");
        assertThat(reviewed.getStatus()).isEqualTo("REVIEWED");
        assertThat(reviewed.getReviewNote()).isEqualTo("Vérifiée avec le relevé Orange");
        assertThatThrownBy(() -> service.review(id, null)).isInstanceOf(ConflictException.class);
        // A reviewed till can still be reopened, which clears the review.
        CashSessionDTO reopened = service.reopen(id, "Erreur de compte");
        assertThat(reopened.getReviewNote()).isNull();
        assertThat(reopened.getStatus()).isEqualTo("OPEN");
        assertThatThrownBy(() -> service.reopen(id, "Encore")).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a closed till cannot be closed again; a method counted twice or an insurer share is refused")
    void closeRefusals() {
        UUID id = session.getId();
        actAs(CASHIER);
        CashSessionCloseRequestDTO twice = counted("800", "850", null);
        twice.getCounts().add(new CashSessionCloseRequestDTO.CountedLine("cash", null, new BigDecimal("800"), null));
        assertThatThrownBy(() -> service.close(id, twice)).isInstanceOf(FieldBusinessException.class);
        CashSessionCloseRequestDTO insurance = counted("800", "850", null);
        insurance.getCounts().add(new CashSessionCloseRequestDTO.CountedLine("INSURANCE", null, BigDecimal.TEN, null));
        assertThatThrownBy(() -> service.close(id, insurance)).isInstanceOf(FieldBusinessException.class);
        CashSessionCloseRequestDTO unknown = counted("800", "850", null);
        unknown.getCounts().add(new CashSessionCloseRequestDTO.CountedLine("TROC", null, BigDecimal.TEN, null));
        assertThatThrownBy(() -> service.close(id, unknown)).isInstanceOf(FieldBusinessException.class);
        CashSessionCloseRequestDTO noCounts = new CashSessionCloseRequestDTO(null);
        assertThatThrownBy(() -> service.close(id, noCounts)).isInstanceOf(FieldBusinessException.class);

        // A method the till never took, counted anyway: expected 0, the gap explained.
        CashSessionCloseRequestDTO extra = counted("800", "850", null);
        extra.getCounts().add(new CashSessionCloseRequestDTO.CountedLine("CARD", null, new BigDecimal("2000"),
            "Ticket de carte trouvé"));
        CashSessionDTO closed = service.close(id, extra);
        assertThat(closed.getTotalDifference()).isEqualByComparingTo("2000");
        CashSessionCloseRequestDTO again = counted("800", "850", null);
        assertThatThrownBy(() -> service.close(id, again)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("PR 10 review: money with no cashier, or taken by someone who holds no till here, is refused")
    void stampNeverReturnsNoTill() {
        assertThatThrownBy(() -> service.stamp(CashTill.BILLING, HOSPITAL, null))
            .isInstanceOf(ClientSafeAccessDeniedException.class)
            .hasMessageContaining("caissier");
        // The platform super-admin on expanded authorities: no till assignment at the hospital.
        UUID superAdmin = UUID.randomUUID();
        assertThatThrownBy(() -> service.stamp(CashTill.BILLING, HOSPITAL, superAdmin))
            .isInstanceOf(ClientSafeAccessDeniedException.class)
            .hasMessageContaining("caisse du jour");
        assertThatThrownBy(() -> service.stamp(CashTill.BILLING, null, CASHIER))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> service.stamp(null, HOSPITAL, CASHIER))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        verify(writes, never()).insertIfAbsent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("PR 10 review: a till holding app-declared mobile money (no operator) can be counted and closed")
    void aTillWithOperatorLessMobileMoneyCloses() {
        // A patient's declaration from the app carries no operator; the cashier confirmed it into this session.
        BillingInvoice invoice = BillingInvoice.builder().invoiceNumber("TWP-2026-000031").build();
        PaymentTransaction declared = PaymentTransaction.builder().invoice(invoice).amount(new BigDecimal("1500"))
            .paymentMethod(PaymentMethod.MOBILE_MONEY).referenceNumber("APP-1").status(PaymentStatus.CONFIRMED)
            .build();
        when(payments.findByCashSessionIdOrderByCreatedAtAsc(session.getId())).thenReturn(List.of(declared));
        when(refunds.findByCashSessionIdOrderByPaidAtAsc(session.getId())).thenReturn(List.of());
        when(sales.findByCashSessionIdOrderByCreatedAtAsc(session.getId())).thenReturn(List.of());
        actAs(CASHIER);

        List<CashSessionCloseRequestDTO.CountedLine> lines = new ArrayList<>();
        lines.add(new CashSessionCloseRequestDTO.CountedLine("MOBILE_MONEY", null, new BigDecimal("1500"), null));
        CashSessionDTO closed = service.close(session.getId(), new CashSessionCloseRequestDTO(lines));

        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(savedCounts).singleElement().satisfies(c -> {
            assertThat(c.getMethod()).isEqualTo("MOBILE_MONEY");
            assertThat(c.getMobileMoneyOperator()).isNull();
            assertThat(c.getExpectedAmount()).isEqualByComparingTo("1500");
            assertThat(c.getDifference()).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("PR 10 review: a mobile-money count with no operator is still refused when the till holds none")
    void anOperatorLessCountIsRefusedWhenTheTillHoldsNone() {
        actAs(CASHIER);
        UUID id = session.getId();
        CashSessionCloseRequestDTO body = counted("800", "850", null);
        body.getCounts().add(new CashSessionCloseRequestDTO.CountedLine("MOBILE_MONEY", null, new BigDecimal("10"),
            "x"));
        assertThatThrownBy(() -> service.close(id, body)).isInstanceOf(FieldBusinessException.class);
        assertThat(session.getStatus()).isEqualTo(CashSessionStatus.OPEN);
    }

    @Test
    @DisplayName("PR 10 review: a shortage and a surplus that sum to zero are still flagged, cash apart")
    void differencesThatNetToZeroAreFlagged() {
        actAs(CASHIER);
        List<CashSessionCloseRequestDTO.CountedLine> lines = new ArrayList<>();
        lines.add(new CashSessionCloseRequestDTO.CountedLine("CASH", null, new BigDecimal("300"),
            "500 F manquants dans le tiroir"));
        lines.add(new CashSessionCloseRequestDTO.CountedLine("MOBILE_MONEY", MobileMoneyOperator.ORANGE_MONEY,
            new BigDecimal("1350"), "500 F de trop sur le relevé"));
        CashSessionDTO closed = service.close(session.getId(), new CashSessionCloseRequestDTO(lines));

        // -500 on cash and +500 on Orange Money: the sum says nothing is wrong.
        assertThat(closed.getTotalDifference()).isEqualByComparingTo("0");
        assertThat(closed.getCashDifference()).isEqualByComparingTo("-500");
        assertThat(closed.getDifferenceLines()).isEqualTo(2);
    }

    @Test
    @DisplayName("QA D3982 (r77): a reopened till shows no old count; re-closed, the new one; earlier closes as history")
    void d3982_aReopenedTillShowsItsCurrentStateAndTheEarlierCloseAsHistory() {
        // Closed at a 100 F cash shortage: expected 800, counted 700.
        actAs(CASHIER);
        service.close(session.getId(), counted("700", "850", "Billet de 100 manquant"));
        savedCounts.forEach(c -> c.setCreatedAt(LocalDateTime.of(2026, 11, 20, 17, 0)));
        UUID id = session.getId();

        actAs(ACCOUNTANT);
        CashSessionDTO reopened = service.reopen(id, "Paiement oublié");
        assertThat(reopened.getStatus()).isEqualTo("OPEN");
        assertThat(reopened.getCountedRound()).as("nothing counted since the reopen").isZero();
        assertThat(reopened.getCashDifference()).as("the old -100 is not the open till's").isNull();
        assertThat(reopened.getTotalDifference()).isNull();
        assertThat(reopened.getDifferenceLines()).isZero();
        assertThat(reopened.getHistory()).singleElement().satisfies(h -> {
            assertThat(h.getCloseRound()).isEqualTo(1);
            assertThat(h.getCashExpected()).isEqualByComparingTo("800");
            assertThat(h.getCashCounted()).isEqualByComparingTo("700");
            assertThat(h.getCashDifference()).isEqualByComparingTo("-100");
            assertThat(h.getDifferenceLines()).isEqualTo(1);
            assertThat(h.getClosedAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 17, 0));
        });
        // The supervisor list reads the same.
        when(sessionRepo.findByHospitalIdAndBusinessDateOrderByTillAscOpenedAtAsc(HOSPITAL, DAY))
            .thenReturn(List.of(session));
        assertThat(service.list(DAY, null)).singleElement()
            .satisfies(row -> assertThat(row.getCashDifference()).isNull());

        // Closed again, counted right: the new count is current, the first close stays history.
        actAs(CASHIER);
        CashSessionDTO again = service.close(id, counted("800", "850", null));
        assertThat(again.getCountedRound()).isEqualTo(2);
        assertThat(again.getCashDifference()).isEqualByComparingTo("0");
        assertThat(again.getDifferenceLines()).isZero();
        assertThat(again.getHistory()).extracting(CashSessionDTO.CloseRound::getCloseRound).containsExactly(1);
    }

    @Test
    @DisplayName("PR 10 review: a till never closed has no difference to show")
    void anOpenTillHasNoDifference() {
        actAs(CASHIER);
        CashSessionDTO open = service.get(session.getId());
        assertThat(open.getCashDifference()).isNull();
        assertThat(open.getDifferenceLines()).isZero();
    }

    @Test
    @DisplayName("PR 10 review: a voided pharmacy payment stays expected in the till, and is named VOIDED")
    void aVoidedPharmacyPaymentStaysExpected() {
        com.example.hms.model.pharmacy.PharmacyPayment voided = new com.example.hms.model.pharmacy.PharmacyPayment();
        voided.setAmount(new BigDecimal("400"));
        voided.setPaymentMethod(PharmacyPaymentMethod.CASH);
        voided.setStatus(com.example.hms.enums.PharmacyPaymentStatus.VOIDED);
        when(pharmacyPayments.findByCashSessionIdOrderByCreatedAtAsc(session.getId())).thenReturn(List.of(voided));
        actAs(CASHIER);

        CashSessionDTO dto = service.get(session.getId());

        // 800 as before, plus the 400 that came in: cancelling the dispense handed nothing back.
        assertThat(dto.getCashToCount()).isEqualByComparingTo("1200");
        assertThat(dto.getMovements()).anySatisfy(m -> {
            assertThat(m.getKind()).isEqualTo("PHARMACY_PAYMENT");
            assertThat(m.getDirection()).isEqualTo("IN");
            assertThat(m.getStatus()).isEqualTo("VOIDED");
        });
    }

    @Test
    @DisplayName("mine: the cashier's own tills of the day")
    void mineAndNoSession() {
        actAs(CASHIER);
        when(sessionRepo.findByHospitalIdAndCashierUserIdAndBusinessDateOrderByTillAsc(HOSPITAL, CASHIER, DAY))
            .thenReturn(List.of(session));
        assertThat(service.mine(null)).singleElement().satisfies(s -> assertThat(s.isMine()).isTrue());
        assertThat(service.actingHospital()).isEqualTo(HOSPITAL);
        actAs(ACCOUNTANT);
        assertThat(service.get(session.getId()).getMovements()).isNotEmpty();
        when(sessionRepo.findByHospitalIdAndBusinessDateOrderByTillAscOpenedAtAsc(HOSPITAL, DAY))
            .thenReturn(List.of(session));
        assertThat(service.dayReport(DAY).sessions()).hasSize(1);
    }

    @Test
    @DisplayName("QA D2950: a pharmacy refund paid out is money out of the pharmacist's till (500 cash + 250 OM sold, "
        + "250 cash handed back: 250 to count, no difference to explain)")
    void aPaidPharmacyRefundLeavesThePharmacistsTill() {
        CashSession till = CashSession.builder().hospitalId(HOSPITAL).cashierUserId(CASHIER).till(CashTill.PHARMACY)
            .businessDate(DAY).status(CashSessionStatus.OPEN).openedAt(LocalDateTime.of(2026, 11, 20, 8, 0)).build();
        till.setId(UUID.randomUUID());
        when(sessionRepo.findLockedByIdAndHospitalId(till.getId(), HOSPITAL)).thenReturn(Optional.of(till));
        when(sessionRepo.findByIdAndHospitalId(till.getId(), HOSPITAL)).thenReturn(Optional.of(till));
        when(countRepo.findBySessionIdOrderByCloseRoundAscMethodAsc(till.getId())).thenReturn(savedCounts);
        PharmacySale cashSale = PharmacySale.builder().totalAmount(new BigDecimal("500"))
            .paymentMethod(PharmacyPaymentMethod.CASH).status(PharmacySaleStatus.COMPLETED)
            .saleDate(LocalDateTime.of(2026, 11, 20, 9, 0)).build();
        PharmacySale orangeSale = PharmacySale.builder().totalAmount(new BigDecimal("250"))
            .paymentMethod(PharmacyPaymentMethod.MOBILE_MONEY).mobileMoneyOperator(MobileMoneyOperator.ORANGE_MONEY)
            .referenceNumber("OM-9").status(PharmacySaleStatus.COMPLETED)
            .saleDate(LocalDateTime.of(2026, 11, 20, 9, 30)).build();
        when(sales.findByCashSessionIdOrderByCreatedAtAsc(till.getId())).thenReturn(List.of(cashSale, orangeSale));
        com.example.hms.model.pharmacy.PharmacyRefund refund = com.example.hms.model.pharmacy.PharmacyRefund.builder()
            .hospitalId(HOSPITAL).amount(new BigDecimal("250")).method(PharmacyPaymentMethod.CASH)
            .status(com.example.hms.enums.PharmacyRefundStatus.PAID).paidBy(CASHIER)
            .paidAt(LocalDateTime.of(2026, 11, 20, 10, 0)).cashSessionId(till.getId()).build();
        refund.setId(UUID.randomUUID());
        when(pharmacyRefunds.findByCashSessionIdOrderByPaidAtAsc(till.getId())).thenReturn(List.of(refund));
        actAs(CASHIER);

        CashSessionDTO dto = service.get(till.getId());

        // 500 cash in, 250 cash handed back: 250 in the drawer. It read 500, and « Sorties 0 ».
        assertThat(dto.getCashToCount()).isEqualByComparingTo("250");
        assertThat(dto.getTotalIn()).isEqualByComparingTo("750");
        assertThat(dto.getTotalOut()).isEqualByComparingTo("250");
        assertThat(dto.getTotalExpected()).isEqualByComparingTo("500");
        assertThat(dto.getMovements()).filteredOn(m -> "OUT".equals(m.getDirection())).singleElement()
            .satisfies(m -> {
                assertThat(m.getKind()).isEqualTo("PHARMACY_REFUND");
                assertThat(m.getId()).isEqualTo(refund.getId());
                assertThat(m.getMethod()).isEqualTo("CASH");
                assertThat(m.getAmount()).isEqualByComparingTo("250");
                assertThat(m.getStatus()).isEqualTo("PAID");
                assertThat(m.getAt()).isEqualTo(LocalDateTime.of(2026, 11, 20, 10, 0));
            });

        // The pharmacist counts what is in the drawer: no difference, nothing to explain.
        CashSessionDTO closed = service.close(till.getId(), counted("250", "250", null));

        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getTotalDifference()).isEqualByComparingTo("0");
        assertThat(closed.getCashDifference()).isEqualByComparingTo("0");
        assertThat(savedCounts).filteredOn(c -> "CASH".equals(c.getMethod())).singleElement()
            .satisfies(c -> assertThat(c.getExpectedAmount()).isEqualByComparingTo("250"));
    }

    @Test
    @DisplayName("QA D2950: a mobile-money pharmacy refund leaves its operator's line, not the cash")
    void aMobileMoneyPharmacyRefundLeavesItsOperatorsLine() {
        com.example.hms.model.pharmacy.PharmacyRefund refund = com.example.hms.model.pharmacy.PharmacyRefund.builder()
            .hospitalId(HOSPITAL).amount(new BigDecimal("150")).method(PharmacyPaymentMethod.MOBILE_MONEY)
            .mobileMoneyOperator(MobileMoneyOperator.ORANGE_MONEY).reference("OM-R1")
            .status(com.example.hms.enums.PharmacyRefundStatus.PAID)
            .paidAt(LocalDateTime.of(2026, 11, 20, 17, 0)).cashSessionId(session.getId()).build();
        when(pharmacyRefunds.findByCashSessionIdOrderByPaidAtAsc(session.getId())).thenReturn(List.of(refund));
        actAs(CASHIER);

        CashSessionDTO dto = service.get(session.getId());

        assertThat(dto.getCashToCount()).isEqualByComparingTo("800");
        assertThat(dto.getMethods()).anySatisfy(line -> {
            assertThat(line.getOperator()).isEqualTo("ORANGE_MONEY");
            assertThat(line.getOutAmount()).isEqualByComparingTo("150");
            assertThat(line.getExpected()).isEqualByComparingTo("700");
        });
    }
}
