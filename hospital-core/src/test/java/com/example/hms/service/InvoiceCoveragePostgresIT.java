package com.example.hms.service;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.mapper.BillingInvoiceMapper;
import com.example.hms.model.BillingInvoice;
import com.example.hms.payload.dto.BillingInvoiceResponseDTO;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.InvoiceAdjustmentRepository;
import com.example.hms.repository.InvoiceItemRepository;
import com.example.hms.repository.InvoicePayerShareRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PayerPlanRepository;
import com.example.hms.repository.PayerPlanRuleRepository;
import com.example.hms.repository.PayerPlanTermsRepository;
import com.example.hms.repository.PayerRepository;
import com.example.hms.repository.PaymentTransactionRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.billing.InvoiceCoverageService;
import com.example.hms.service.payment.PaymentReferenceRegistry;
import com.example.hms.utility.InvoiceBalance;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 7 on real PostgreSQL built by Liquibase (V305 + V306): BC2's invoice issued under RAMU 80 %
 * (lab excluded, annual ceiling 150 000) takes a payer share of 5 600 and folds the check-in copay against the
 * patient's 2 900; two invoices issued at the same moment cannot both use the last 2 000 of the annual ceiling (the
 * coverage's row lock); a rejection moves the share back to the patient and keeps the original row; and the V306
 * constraints hold. Foreign keys are off for the billing and coverage rows, whose patient and hospital say nothing
 * here; the hospital, the lines' owner assignment and its role are real rows.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@org.springframework.context.annotation.Import(com.example.hms.security.EncryptionKeyHolder.class)
@org.junit.jupiter.api.Timeout(180)
class InvoiceCoveragePostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_invoice_coverage")
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

    private static final LocalDate SERVICE_DAY = LocalDate.of(2026, 11, 17);

    @Autowired private BillingInvoiceRepository invoices;
    @Autowired private InvoiceItemRepository items;
    @Autowired private InvoicePayerShareRepository shares;
    @Autowired private com.example.hms.repository.InvoicePayerShareLineRepository shareLines;
    @Autowired private InvoiceAdjustmentRepository adjustments;
    @Autowired private PatientInsuranceRepository insurances;
    @Autowired private PayerRepository payers;
    @Autowired private PayerPlanRepository plans;
    @Autowired private PayerPlanTermsRepository terms;
    @Autowired private PayerPlanRuleRepository rules;
    @Autowired private PriceListItemRepository priceItems;
    @Autowired private PaymentTransactionRepository payments;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private final RoleValidator roles = mock(RoleValidator.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);

    private InvoiceCoverageService coverage;
    private BillingInvoiceServiceImpl billing;
    private TransactionTemplate tx;
    private UUID hospitalA;
    private UUID patient;
    private UUID insurance;
    private UUID plan;
    private UUID desk;
    private UUID cashier;

    /** The French locale set for the refusals never leaks into a test run after this one. */
    @org.junit.jupiter.api.AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        for (String table : List.of("billing.billing_invoices", "billing.invoice_items", "billing.invoice_payer_shares",
                "billing.payment_transactions", "billing.payers", "billing.payer_plans", "billing.payer_plan_terms",
                "billing.payer_plan_rules", "clinical.patient_insurances", "security.user_role_hospital_assignment")) {
            jdbc.execute("ALTER TABLE " + table + " DISABLE TRIGGER ALL");
        }
        hospitalA = UUID.randomUUID();
        patient = UUID.randomUUID();
        cashier = UUID.randomUUID();
        tx = new TransactionTemplate(transactions);
        when(scope.requirePinned()).thenReturn(hospitalA);
        when(roles.requireActiveHospitalId()).thenReturn(hospitalA);
        when(roles.getCurrentUserId()).thenReturn(cashier);
        when(roles.canDecidePayerShare(any(), any())).thenReturn(true);
        coverage = new InvoiceCoverageService(shares, invoices, items, adjustments, insurances, payers, plans, terms,
            rules, priceItems, roles, scope, mock(AuditEventLogService.class),
            Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC));
        BillingInvoiceMapper mapper = mock(BillingInvoiceMapper.class);
        when(mapper.toBillingInvoiceResponseDTO(any())).thenReturn(new BillingInvoiceResponseDTO());
        coverage.setShareLineRepository(shareLines);
        billing = new BillingInvoiceServiceImpl(invoices, mock(PatientRepository.class), mock(HospitalRepository.class),
            mock(EncounterRepository.class), items, mock(PdfInvoiceService.class), mapper, roles, payments, scope,
            mock(PatientHospitalRegistrationRepository.class), mock(InvoiceNumberAllocator.class), transactions,
            mock(PaymentReferenceRegistry.class));
        billing.setCoverageService(coverage);
        billing.setAdjustmentRepository(adjustments);
        billing.setEntityManager(entityManager);

        // The hospital row: the coverage is found through its assignment's hospital (a join).
        jdbc.update("INSERT INTO hospital.hospitals (id, name, code, active, created_at, updated_at)"
            + " VALUES (?, ?, ?, TRUE, now(), now())", hospitalA, "IT-Coverage " + hospitalA,
            "ITC-" + hospitalA.toString().substring(0, 8));
        // The lines' owner: a real assignment row at hospital A, with its role (FK checks are off).
        UUID role = UUID.randomUUID();
        jdbc.update("INSERT INTO security.roles (id, code, name, created_at, updated_at) VALUES (?, ?, ?, now(), now())",
            role, "ROLE_C_" + role.toString().substring(0, 8), "Caisse " + role.toString().substring(0, 8));
        desk = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO security.user_role_hospital_assignment (id, user_id, role_id, hospital_id, is_active,
                assigned_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, true, now(), now(), now())
            """, desk, UUID.randomUUID(), role, hospitalA);

        // RAMU, Formule Base 80 %, laboratoire exclu, plafond annuel 150 000 F.
        UUID payer = UUID.randomUUID();
        jdbc.update("INSERT INTO billing.payers (id, hospital_id, code, name, payer_type) VALUES (?, ?, ?, 'RAMU', 'AMU')",
            payer, hospitalA, "RAMU" + payer.toString().substring(0, 4).toUpperCase());
        plan = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO billing.payer_plans (id, hospital_id, payer_id, code, name, effective_from)
            VALUES (?, ?, ?, 'BASE', 'Formule Base', DATE '2026-01-01')
            """, plan, hospitalA, payer);
        jdbc.update("""
            INSERT INTO billing.payer_plan_terms (hospital_id, plan_id, default_coverage_percent,
                annual_ceiling_per_beneficiary, effective_from)
            VALUES (?, ?, 80, 150000, DATE '2026-01-01')
            """, hospitalA, plan);
        jdbc.update("""
            INSERT INTO billing.payer_plan_rules (hospital_id, plan_id, category, excluded, effective_from)
            VALUES (?, ?, 'LAB_TEST', true, DATE '2026-01-01')
            """, hospitalA, plan);
        // BC2's coverage at hospital A, linked to the plan.
        insurance = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO clinical.patient_insurances (id, patient_id, assignment_id, provider_name, policy_number,
                is_primary, payer_plan_id, effective_date, created_at, updated_at)
            VALUES (?, ?, ?, 'RAMU', 'BC2-001', true, ?, DATE '2026-01-01', now(), now())
            """, insurance, patient, desk, plan);
    }

    private <T> T inTx(Supplier<T> call) {
        return tx.execute(status -> call.get());
    }

    /** A DRAFT visit invoice of BC2 at hospital A: consultation 2 000, TDR 1 500 (lab), X-ray 5 000. */
    private UUID draftBc2(UUID encounter) {
        UUID invoice = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO billing.billing_invoices (id, patient_id, hospital_id, encounter_id, invoice_number,
                invoice_date, due_date, total_amount, amount_paid, status, check_in_copay, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 8500, 0, 'DRAFT', false, now(), now())
            """, invoice, patient, hospitalA, encounter, "CV-" + invoice.toString().substring(0, 8), SERVICE_DAY,
            SERVICE_DAY.plusDays(30));
        // QA D3980: what each line bills, as the checkout stamps it (a line with no category is never covered).
        line(invoice, "Consultation", "SERVICE", "CONSULTATION", "2000", 1);
        line(invoice, "TDR paludisme", "LAB_TEST", "LAB_TEST", "1500", 2);
        line(invoice, "Radio thorax", "SERVICE", "IMAGING", "5000", 3);
        return invoice;
    }

    private void line(UUID invoice, String description, String category, String coverage, String price,
                      int minute) {
        jdbc.update("""
            INSERT INTO billing.invoice_items (id, billing_invoice_id, item_description, quantity, item_category,
                unit_price, total_price, assignment_id, price_source, coverage_category, created_at, updated_at)
            VALUES (gen_random_uuid(), ?, ?, 1, ?, ?, ?, ?, 'MANUAL', ?,
                TIMESTAMP '2026-11-17 09:00' + make_interval(mins => ?), now())
            """, invoice, description, category, new BigDecimal(price), new BigDecimal(price), desk, coverage, minute);
    }

    /** What updateInvoice does when a draft is issued: SENT, the payer share, the status, then the copay fold. */
    private BigDecimal issue(UUID invoiceId) {
        return inTx(() -> {
            BillingInvoice invoice = invoices.findLockedById(invoiceId).orElseThrow();
            invoice.setStatus(InvoiceStatus.SENT);
            BigDecimal share = coverage.applyAtIssue(invoice, cashier);
            invoice.setStatus(InvoiceBalance.settledStatusOf(invoice));
            invoices.save(invoice);
            billing.creditCheckInCopays(invoiceId);
            return share;
        });
    }

    private BigDecimal column(String column, UUID invoice) {
        return jdbc.queryForObject("SELECT " + column + " FROM billing.billing_invoices WHERE id = ?", BigDecimal.class,
            invoice);
    }

    private void refusedBy(String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("BC2 issued: payers 5 600, the 2 000 copay folds against the patient's 2 900, 900 left; a rejection moves 5 600 back")
    void issueFoldRejectOnTheRealSchema() {
        UUID encounter = UUID.randomUUID();
        UUID visit = draftBc2(encounter);
        UUID copay = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO billing.billing_invoices (id, patient_id, hospital_id, encounter_id, invoice_number,
                invoice_date, due_date, total_amount, amount_paid, status, check_in_copay, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 2000, 2000, 'PAID', true, now() - interval '2 hours', now())
            """, copay, patient, hospitalA, encounter, "TM-" + copay.toString().substring(0, 8), SERVICE_DAY,
            SERVICE_DAY);
        jdbc.update("""
            INSERT INTO billing.payment_transactions (id, invoice_id, amount, payment_date, payment_method, status,
                created_at, updated_at)
            VALUES (gen_random_uuid(), ?, 2000, ?, 'CASH', 'CONFIRMED', now() - interval '2 hours', now())
            """, copay, SERVICE_DAY);

        assertThat(issue(visit)).isEqualByComparingTo("5600");

        assertThat(column("payer_share_total", visit)).isEqualByComparingTo("5600");
        assertThat(column("amount_paid", visit)).isEqualByComparingTo("2000");
        assertThat(jdbc.queryForObject("SELECT status FROM billing.billing_invoices WHERE id = ?", String.class, visit))
            .isEqualTo("PARTIALLY_PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM billing.billing_invoices WHERE id = ?", String.class, copay))
            .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForList("""
            SELECT payer_share_amount FROM billing.invoice_items WHERE billing_invoice_id = ? ORDER BY created_at
            """, BigDecimal.class, visit)).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("1600"), BigDecimal.ZERO, new BigDecimal("4000"));
        assertThat(inTx(() -> InvoiceBalance.dueOf(invoices.findById(visit).orElseThrow())))
            .isEqualByComparingTo("900");

        // Folded once (D936): a second pass finds the copay gone.
        inTx(() -> {
            billing.creditCheckInCopays(visit);
            return null;
        });
        assertThat(column("amount_paid", visit)).isEqualByComparingTo("2000");

        UUID share = jdbc.queryForObject("SELECT id FROM billing.invoice_payer_shares WHERE invoice_id = ?", UUID.class,
            visit);
        inTx(() -> coverage.reject(visit, share, "Bénéficiaire non à jour de cotisation"));
        assertThat(column("payer_share_total", visit)).isEqualByComparingTo("0");
        assertThat(inTx(() -> InvoiceBalance.dueOf(invoices.findById(visit).orElseThrow())))
            .as("the balance rose by 5 600").isEqualByComparingTo("6500");
        assertThat(jdbc.queryForMap("SELECT status, amount, rejection_reason FROM billing.invoice_payer_shares WHERE id = ?",
            share)).containsEntry("status", "REJECTED").containsEntry("rejection_reason",
            "Bénéficiaire non à jour de cotisation")
            .satisfies(row -> assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("5600"));
    }

    @Test
    @DisplayName("148 000 used of 150 000: two invoices issued at the same moment share the last 2 000, never 4 000")
    void twoIssuesTogetherCannotBothUseTheLastOfTheCeiling() throws Exception {
        UUID earlier = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO billing.invoice_payer_shares (invoice_id, hospital_id, patient_insurance_id, payer_plan_id,
                amount, coverage_year, status)
            VALUES (?, ?, ?, ?, 148000, 2026, 'OPEN')
            """, earlier, hospitalA, insurance, plan);
        UUID first = draftBc2(UUID.randomUUID());
        UUID second = draftBc2(UUID.randomUUID());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (UUID invoice : List.of(first, second)) {
                runs.add(pool.submit(() -> {
                    LocaleContextHolder.setLocale(Locale.FRENCH);
                    try {
                        start.await();
                        issue(invoice);
                    } catch (Throwable thrown) {
                        failures.add(thrown);
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

        assertThat(failures).isEmpty();
        BigDecimal granted = column("payer_share_total", first).add(column("payer_share_total", second));
        assertThat(granted).isEqualByComparingTo("2000");
        assertThat(jdbc.queryForObject("""
            SELECT COALESCE(SUM(amount), 0) FROM billing.invoice_payer_shares
             WHERE patient_insurance_id = ? AND coverage_year = 2026 AND status IN ('OPEN', 'CLAIMED', 'PAID')
            """, BigDecimal.class, insurance)).isEqualByComparingTo("150000");
    }

    @Test
    @DisplayName("V306 constraints: whole francs, a rejection names its reason, a decision names its decider, one live share")
    void theDatabaseHoldsTheRules() {
        UUID invoice = draftBc2(UUID.randomUUID());
        String insert = """
            INSERT INTO billing.invoice_payer_shares (invoice_id, hospital_id, patient_insurance_id, payer_plan_id,
                amount, coverage_year, status, rejection_reason, decided_by, decided_at)
            VALUES (?, ?, ?, ?, ?, 2026, ?, ?, ?, ?)
            """;
        java.sql.Timestamp now = java.sql.Timestamp.valueOf(java.time.LocalDateTime.now());
        refusedBy(insert, invoice, hospitalA, insurance, plan, new BigDecimal("100.50"), "OPEN", null, null, null);
        refusedBy(insert, invoice, hospitalA, insurance, plan, new BigDecimal("-1"), "OPEN", null, null, null);
        refusedBy(insert, invoice, hospitalA, insurance, plan, new BigDecimal("100"), "REJECTED", " ", cashier, now);
        refusedBy(insert, invoice, hospitalA, insurance, plan, new BigDecimal("100"), "CLAIMED", null, null, null);
        refusedBy(insert, invoice, hospitalA, insurance, plan, new BigDecimal("100"), "WAIVED", null, null, null);
        jdbc.update(insert, invoice, hospitalA, insurance, plan, new BigDecimal("100"), "OPEN", null, null, null);
        // One live share per invoice and coverage; a rejected one stays beside it in the history.
        refusedBy(insert, invoice, hospitalA, insurance, plan, new BigDecimal("200"), "CLAIMED", null, cashier, now);
        jdbc.update(insert, invoice, hospitalA, insurance, plan, new BigDecimal("300"), "REJECTED", "Hors délai",
            cashier, now);
        refusedBy("UPDATE billing.billing_invoices SET payer_share_total = 10.5 WHERE id = ?", invoice);
        refusedBy("UPDATE billing.invoice_items SET payer_share_amount = -1 WHERE billing_invoice_id = ?", invoice);
    }
    @Test
    @DisplayName("claims PR 1 (G1, G2): « Parts assureurs » filters by payer, period and status, pages, and names the policy")
    void theShareListFiltersPagesAndNamesThePolicy() {
        seedPatient();
        UUID visit = draftBc2(UUID.randomUUID());
        assertThat(issue(visit)).isEqualByComparingTo("5600");
        // Another hospital's invoice, share and payer: never on hospital A's list, whatever the filters.
        UUID hospitalB = UUID.randomUUID();
        UUID strayInvoice = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO billing.billing_invoices (id, patient_id, hospital_id, invoice_number, invoice_date, due_date,
                total_amount, amount_paid, status, check_in_copay, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, 900, 0, 'SENT', false, now(), now())
            """, strayInvoice, patient, hospitalB, "XB-" + strayInvoice.toString().substring(0, 8), SERVICE_DAY,
            SERVICE_DAY.plusDays(30));
        jdbc.update("""
            INSERT INTO billing.invoice_payer_shares (id, invoice_id, hospital_id, patient_insurance_id, payer_plan_id,
                amount, coverage_year, status, created_at, updated_at)
            VALUES (gen_random_uuid(), ?, ?, ?, ?, 900, 2026, 'OPEN', now(), now())
            """, strayInvoice, hospitalB, insurance, plan);
        UUID payer = jdbc.queryForObject("SELECT payer_id FROM billing.payer_plans WHERE id = ?", UUID.class, plan);

        com.example.hms.payload.dto.billing.PayerSharePageDTO all = inTx(() -> coverage.searchAtHospital(null));
        assertThat(all.getTotalElements()).isEqualTo(1);
        assertThat(all.getTotalAmount()).isEqualByComparingTo("5600");
        assertThat(all.getContent()).singleElement().satisfies(row -> {
            assertThat(row.getPatientName()).contains("Awa");
            assertThat(row.getPolicyNumber()).isEqualTo("BC2-001");
            assertThat(row.getPayerType()).isEqualTo("AMU");
            assertThat(row.getLines()).extracting(com.example.hms.payload.dto.billing.PayerShareLineDTO::getCategory)
                .containsExactly("CONSULTATION", "IMAGING");
        });

        // By payer and November: found; by another payer, by October or as PAID: not.
        assertThat(inTx(() -> coverage.searchAtHospital(new InvoiceCoverageService.ShareFilter(null, payer, plan,
            patient, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), 0, 10))).getTotalElements()).isEqualTo(1);
        assertThat(inTx(() -> coverage.searchAtHospital(new InvoiceCoverageService.ShareFilter(null,
            UUID.randomUUID(), null, null, null, null, null, null))).getTotalElements()).isZero();
        assertThat(inTx(() -> coverage.searchAtHospital(new InvoiceCoverageService.ShareFilter(null, null, null, null,
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, null))).getTotalElements()).isZero();
        assertThat(inTx(() -> coverage.searchAtHospital(new InvoiceCoverageService.ShareFilter(
            List.of(com.example.hms.enums.PayerShareStatus.PAID), null, null, null, null, null, null, null)))
            .getTotalElements()).isZero();
        // A page past the end is empty, with the same totals.
        com.example.hms.payload.dto.billing.PayerSharePageDTO second = inTx(() -> coverage.searchAtHospital(
            new InvoiceCoverageService.ShareFilter(null, null, null, null, null, null, 1, 1)));
        assertThat(second.getContent()).isEmpty();
        assertThat(second.getTotalElements()).isEqualTo(1);
        assertThat(second.getTotalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("claims PR 1 (QA D3984): an order's number reaches its live lines at its hospital, and nothing else")
    void anOrdersNumberReachesItsLinesOnly() {
        UUID visit = draftBc2(UUID.randomUUID());
        UUID order = UUID.randomUUID();
        // The X-ray line was posted for the imaging order; the V383 CHECK wants both source columns together.
        jdbc.update("""
            UPDATE billing.invoice_items SET source_order_type = 'IMAGING_ORDER', source_order_id = ?
             WHERE billing_invoice_id = ? AND item_description = 'Radio thorax'
            """, order, visit);
        com.example.hms.service.billing.InvoiceLinePreAuthorisations copier =
            new com.example.hms.service.billing.InvoiceLinePreAuthorisations(items, null,
                Clock.fixed(Instant.parse("2026-11-18T08:00:00Z"), ZoneOffset.UTC));

        assertThat(inTx(() -> copier.copyFromOrder(com.example.hms.service.billing.BillableOrderType.IMAGING_ORDER,
            order, UUID.randomUUID(), "BON-117", cashier))).as("another hospital").isZero();
        assertThat(inTx(() -> copier.copyFromOrder(com.example.hms.service.billing.BillableOrderType.PROCEDURE_ORDER,
            order, hospitalA, "BON-117", cashier))).as("another kind of order").isZero();
        assertThat(inTx(() -> copier.copyFromOrder(com.example.hms.service.billing.BillableOrderType.IMAGING_ORDER,
            order, hospitalA, "BON-117", null))).isEqualTo(1);

        assertThat(jdbc.queryForList("""
            SELECT pre_authorisation_number FROM billing.invoice_items
             WHERE billing_invoice_id = ? AND pre_authorisation_number IS NOT NULL
            """, String.class, visit)).containsExactly("BON-117");
        // The amounts are not touched.
        assertThat(jdbc.queryForObject("SELECT SUM(total_price) FROM billing.invoice_items WHERE billing_invoice_id = ?",
            BigDecimal.class, visit)).isEqualByComparingTo("8500");
        // The V383 CHECKs: one source column without the other, a number without its stamp, or a blank number.
        refusedBy("UPDATE billing.invoice_items SET source_order_id = NULL WHERE source_order_id = ?", order);
        refusedBy("UPDATE billing.invoice_items SET source_order_type = 'NOTHING' WHERE source_order_id = ?", order);
        refusedBy("UPDATE billing.invoice_items SET pre_authorisation_recorded_at = NULL WHERE source_order_id = ?",
            order);
        refusedBy("UPDATE billing.invoice_items SET pre_authorisation_number = '  ' WHERE source_order_id = ?", order);
        // A cancelled invoice's line no longer takes a number.
        jdbc.update("UPDATE billing.billing_invoices SET status = 'CANCELLED' WHERE id = ?", visit);
        assertThat(inTx(() -> copier.copyFromOrder(com.example.hms.service.billing.BillableOrderType.IMAGING_ORDER,
            order, hospitalA, "BON-118", cashier))).isZero();
    }
    /** The list names the patient: BC2 is a real row here (its foreign keys are not this test's subject). */
    private void seedPatient() {
        tx.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("""
                INSERT INTO clinical.patients (id, user_id, first_name, last_name, date_of_birth, phone_number_primary,
                    is_active, chart_restricted, created_at, updated_at)
                VALUES (?, ?, 'Awa', 'Ouedraogo', DATE '1990-06-15', '+226 70 28 43 17', true, false, now(), now())
                """, patient, UUID.randomUUID());
        });
    }

    @Test
    @DisplayName("review of #1353: a share the payer rejected still lists the acts it claimed, with their amounts")
    void aRejectedShareKeepsTheActsItClaimed() {
        seedPatient();
        UUID visit = draftBc2(UUID.randomUUID());
        assertThat(issue(visit)).isEqualByComparingTo("5600");
        UUID share = jdbc.queryForObject("SELECT id FROM billing.invoice_payer_shares WHERE invoice_id = ?",
            UUID.class, visit);
        assertThat(jdbc.queryForList("""
            SELECT amount FROM billing.invoice_payer_share_lines WHERE share_id = ? ORDER BY amount
            """, BigDecimal.class, share)).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("1600"), new BigDecimal("4000"));

        inTx(() -> coverage.reject(visit, share, "Hors délai"));
        // The live split is cleared with the share...
        assertThat(column("payer_share_total", visit)).isEqualByComparingTo("0");

        // ...but the closed list still shows what was claimed.
        com.example.hms.payload.dto.billing.PayerSharePageDTO rejected = inTx(() -> coverage.searchAtHospital(
            new InvoiceCoverageService.ShareFilter(List.of(com.example.hms.enums.PayerShareStatus.REJECTED), null,
                null, null, null, null, null, null)));
        assertThat(rejected.getContent()).singleElement().satisfies(row -> {
            assertThat(row.getAmount()).isEqualByComparingTo("5600");
            assertThat(row.getLines()).extracting(com.example.hms.payload.dto.billing.PayerShareLineDTO::getCategory)
                .containsExactly("CONSULTATION", "IMAGING");
            assertThat(row.getLines()).extracting(com.example.hms.payload.dto.billing.PayerShareLineDTO::getPayerShare)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1600"), new BigDecimal("4000"));
        });
        refusedBy("UPDATE billing.invoice_payer_share_lines SET amount = 10.5 WHERE share_id = ?", share);
    }
}
