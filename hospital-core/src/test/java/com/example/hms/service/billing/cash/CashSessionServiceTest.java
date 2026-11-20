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
            users, roles, scope, days, mock(AuditEventLogService.class), CLOCK);
        when(scope.requirePinned()).thenReturn(HOSPITAL);
        when(days.today()).thenReturn(DAY);
        when(roles.canSuperviseTills(ACCOUNTANT, HOSPITAL)).thenReturn(true);
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
        assertThatThrownBy(() -> service.close(id, counted("700", "850", null)))
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
        assertThatThrownBy(() -> service.close(id, counted("800", null, null)))
            .isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> service.close(id, counted("800.50", "850", "x")))
            .isInstanceOf(FieldBusinessException.class);
        verify(countRepo, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("only the session's cashier closes it; another cashier's session answers as missing")
    void onlyTheCashierCloses() {
        UUID id = session.getId();
        actAs(ACCOUNTANT);
        assertThatThrownBy(() -> service.close(id, counted("800", "850", null)))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        actAs(UUID.randomUUID());
        assertThatThrownBy(() -> service.close(id, counted("800", "850", null)))
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
}
