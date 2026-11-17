package com.example.hms.repository;

import com.example.hms.enums.InvoiceRefundStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.payload.dto.billing.InvoiceRefundDTO;
import com.example.hms.payload.dto.billing.InvoiceRefundRequestDTO;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.PdfInvoiceService;
import com.example.hms.service.billing.InvoiceRefundService;
import com.example.hms.service.payment.PaymentReferenceRegistry;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 8 on real PostgreSQL built by Liquibase (V307): two cashiers paying out the same approved
 * refund at once pay it once; two requests made together never ask for more than is due; another hospital sees
 * nothing; and the V307 constraints hold (no self-approval, whole francs, mobile money with operator and reference).
 * Foreign keys are off for the billing rows, whose patient and hospital say nothing here.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@org.springframework.context.annotation.Import(com.example.hms.security.EncryptionKeyHolder.class)
@org.junit.jupiter.api.Timeout(180)
class InvoiceRefundPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_invoice_refunds")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    @DynamicPropertySource
    static void realDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired private BillingInvoiceRepository invoices;
    @Autowired private InvoiceRefundRepository refunds;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private final RoleValidator roles = mock(RoleValidator.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);

    private InvoiceRefundService service;
    private TransactionTemplate tx;
    private UUID hospitalA;
    private UUID cashier;
    private UUID admin;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        for (String table : List.of("billing.billing_invoices", "billing.invoice_refunds")) {
            jdbc.execute("ALTER TABLE " + table + " DISABLE TRIGGER ALL");
        }
        hospitalA = UUID.randomUUID();
        cashier = UUID.randomUUID();
        admin = UUID.randomUUID();
        tx = new TransactionTemplate(transactions);
        service = new InvoiceRefundService(invoices, refunds, mock(PaymentReferenceRegistry.class),
            mock(UserRepository.class), roles, scope, mock(PdfInvoiceService.class), mock(AuditEventLogService.class),
            Clock.systemUTC());
        when(scope.requirePinned()).thenReturn(hospitalA);
        when(roles.requireActiveHospitalId()).thenReturn(hospitalA);
        when(roles.canRequestRefund(any(), eq(hospitalA))).thenReturn(true);
        when(roles.canPayRefund(any(), eq(hospitalA))).thenReturn(true);
        when(roles.canApproveRefund(eq(admin), eq(hospitalA))).thenReturn(true);
    }

    private <T> T inTx(Supplier<T> call) {
        return tx.execute(status -> call.get());
    }

    /** BC2 in r53: a 2 000 F copay folded onto a visit priced 1 500 F, at {@code hospitalId}. */
    private UUID overpaidInvoice(UUID hospitalId) {
        UUID invoice = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO billing.billing_invoices (id, patient_id, hospital_id, invoice_number, invoice_date, due_date,
                total_amount, amount_paid, status, check_in_copay, created_at, updated_at)
            VALUES (?, ?, ?, ?, current_date, current_date + 30, 1500, 2000, 'PAID', false, now(), now())
            """, invoice, UUID.randomUUID(), hospitalId, "RB-" + invoice.toString().substring(0, 8));
        return invoice;
    }

    private InvoiceRefundDTO request(UUID invoice, String amount) {
        when(roles.getCurrentUserId()).thenReturn(cashier);
        return inTx(() -> service.request(invoice, InvoiceRefundRequestDTO.builder()
            .amount(new BigDecimal(amount)).method("CASH").reason("Trop-perçu du ticket modérateur").build()));
    }

    private InvoiceRefundDTO approve(UUID invoice, UUID refund) {
        when(roles.getCurrentUserId()).thenReturn(admin);
        return inTx(() -> service.approve(invoice, refund, null));
    }

    private BigDecimal refunded(UUID invoice) {
        return jdbc.queryForObject("SELECT amount_refunded FROM billing.billing_invoices WHERE id = ?",
            BigDecimal.class, invoice);
    }

    /** Runs {@code call} on two threads at once; returns what each threw (null when it succeeded). */
    private List<Throwable> together(Supplier<?> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> outcomes = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    LocaleContextHolder.setLocale(Locale.FRENCH);
                    try {
                        start.await();
                        inTx(call);
                        outcomes.add(null);
                    } catch (Throwable thrown) {
                        outcomes.add(thrown);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return outcomes;
    }

    @Test
    @DisplayName("two cashiers paying out the same approved refund at once: one wins, the money moves once")
    void aConcurrentPayoutPaysOnce() throws Exception {
        UUID invoice = overpaidInvoice(hospitalA);
        InvoiceRefundDTO asked = request(invoice, "500");
        approve(invoice, asked.getId());
        when(roles.getCurrentUserId()).thenReturn(cashier);

        List<Throwable> outcomes = together(() -> service.pay(invoice, asked.getId()));

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.stream().filter(java.util.Objects::isNull).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(java.util.Objects::nonNull).toList())
            .singleElement().isInstanceOf(ConflictException.class);
        assertThat(refunded(invoice)).isEqualByComparingTo("500");
        assertThat(jdbc.queryForObject("SELECT status FROM billing.invoice_refunds WHERE id = ?", String.class,
            asked.getId())).isEqualTo("PAID");
        // The money that came in is untouched.
        assertThat(jdbc.queryForObject("SELECT amount_paid FROM billing.billing_invoices WHERE id = ?",
            BigDecimal.class, invoice)).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("two requests made together never ask for more than is due: the second is refused")
    void twoRequestsTogetherNeverExceedTheRefundDue() throws Exception {
        UUID invoice = overpaidInvoice(hospitalA);
        when(roles.getCurrentUserId()).thenReturn(cashier);

        List<Throwable> outcomes = together(() -> service.request(invoice, InvoiceRefundRequestDTO.builder()
            .amount(new BigDecimal("300")).method("CASH").reason("Trop-perçu").build()));

        assertThat(outcomes.stream().filter(java.util.Objects::isNull).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(java.util.Objects::nonNull).toList())
            .singleElement().isInstanceOfSatisfying(FieldBusinessException.class,
                refusal -> assertThat(refusal.getMessage()).contains("200 F CFA"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing.invoice_refunds WHERE invoice_id = ?",
            Integer.class, invoice)).isEqualTo(1);
    }

    @Test
    @DisplayName("the D1900 flow on the real schema, and another hospital sees nothing")
    void theFlowAndTenancy() {
        UUID invoice = overpaidInvoice(hospitalA);
        InvoiceRefundDTO asked = request(invoice, "500");
        approve(invoice, asked.getId());
        when(roles.getCurrentUserId()).thenReturn(cashier);
        InvoiceRefundDTO paid = inTx(() -> service.pay(invoice, asked.getId()));
        assertThat(paid.getStatus()).isEqualTo(InvoiceRefundStatus.PAID.name());
        assertThat(refunded(invoice)).isEqualByComparingTo("500");
        assertThat(inTx(() -> refunds.findPaidByCashier(cashier, hospitalA,
            java.time.LocalDate.now().minusDays(1).atStartOfDay(), java.time.LocalDate.now().plusDays(2).atStartOfDay())))
            .hasSize(1);

        UUID hospitalB = UUID.randomUUID();
        when(scope.requirePinned()).thenReturn(hospitalB);
        when(roles.requireActiveHospitalId()).thenReturn(hospitalB);
        when(roles.canRequestRefund(any(), eq(hospitalB))).thenReturn(true);
        when(roles.canApproveRefund(any(), eq(hospitalB))).thenReturn(true);
        assertThatThrownBy(() -> inTx(() -> service.list(invoice))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> inTx(() -> service.cancel(invoice, asked.getId(), null)))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(inTx(() -> refunds.findByHospitalIdAndStatusInOrderByCreatedAtAsc(hospitalB,
            List.of(InvoiceRefundStatus.values())))).isEmpty();
    }

    @Test
    @DisplayName("V307 constraints: no self-approval, whole francs, mobile money with operator and reference")
    void theDatabaseHoldsTheRules() {
        UUID invoice = overpaidInvoice(hospitalA);
        UUID same = UUID.randomUUID();
        String insert = """
            INSERT INTO billing.invoice_refunds (id, invoice_id, hospital_id, amount, method, mobile_money_operator,
                reference, reason, status, requested_by, requested_at, decided_by, decided_at)
            VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'Trop-perçu', ?, ?, now(), ?, ?)
            """;
        // D-3: the person who decides is never the person who asked.
        assertThatThrownBy(() -> jdbc.update(insert, invoice, hospitalA, new BigDecimal("500"), "CASH", null, null,
            "APPROVED", same, same, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now())))
            .isInstanceOf(DataIntegrityViolationException.class);
        // Whole francs, more than 0.
        assertThatThrownBy(() -> jdbc.update(insert, invoice, hospitalA, new BigDecimal("500.50"), "CASH", null, null,
            "REQUESTED", same, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, invoice, hospitalA, BigDecimal.ZERO, "CASH", null, null,
            "REQUESTED", same, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        // Mobile money: an operator and a reference.
        assertThatThrownBy(() -> jdbc.update(insert, invoice, hospitalA, new BigDecimal("500"), "MOBILE_MONEY", null,
            "OM-1", "REQUESTED", same, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        // An unknown method is refused (INSURANCE is a PaymentMethod constant: the service refuses it).
        assertThatThrownBy(() -> jdbc.update(insert, invoice, hospitalA, new BigDecimal("500"), "CRYPTO", null,
            null, "REQUESTED", same, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        // A decided refund names its decider; a well-formed request passes.
        assertThatThrownBy(() -> jdbc.update(insert, invoice, hospitalA, new BigDecimal("500"), "CASH", null, null,
            "APPROVED", same, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, invoice, hospitalA, new BigDecimal("500"), "MOBILE_MONEY", "ORANGE_MONEY", "OM-OUT-1",
            "REQUESTED", same, null, null);
        // amount_refunded is whole and never negative.
        assertThatThrownBy(() -> jdbc.update("UPDATE billing.billing_invoices SET amount_refunded = -1 WHERE id = ?",
            invoice)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
