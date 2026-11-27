package com.example.hms.service.billing.claim;

import com.example.hms.enums.ClaimRefusalClass;
import com.example.hms.enums.ClaimRefusalResolution;
import com.example.hms.enums.ClaimWriteOffReason;
import com.example.hms.enums.InsurerPaymentMethod;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.BillingInvoice;
import com.example.hms.payload.dto.billing.InsurerClaimBatchDTO;
import com.example.hms.payload.dto.billing.InsurerClaimBatchRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimBatchSharesRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimBatchSubmitRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimLineDTO;
import com.example.hms.payload.dto.billing.InsurerClaimRefusalRequestDTO;
import com.example.hms.payload.dto.billing.InsurerClaimWriteOffDTO;
import com.example.hms.payload.dto.billing.InsurerClaimWriteOffRequestDTO;
import com.example.hms.payload.dto.billing.InsurerPaymentAllocationRequestDTO;
import com.example.hms.payload.dto.billing.InsurerPaymentDTO;
import com.example.hms.payload.dto.billing.InsurerPaymentRequestDTO;
import com.example.hms.payload.dto.billing.InsurerReceivablesDTO;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.InvoicePayerShareRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.PdfClaimBordereauService;
import com.example.hms.service.billing.InvoiceCoverageService;
import com.example.hms.utility.InvoiceBalance;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Insurer claims plan PRs 2 to 4 on real PostgreSQL built by Liquibase (V455 to V458, QA D3983): the bordereau from
 * draft to sent, its exports, the payer's remittances with partial allocation, refusals by class, four-eyes
 * write-offs, the reports, and above all the money: on every line and share, after every step,
 * {@code claimed = paid + re-billed + written off + outstanding}, and no invoice total or patient payment moves.
 * The services are the real beans (their transactions and row locks are the real ones); only the caller's identity
 * and roles are stubbed. Foreign keys are off for the invoice and coverage rows, as in InvoiceCoveragePostgresIT.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({com.example.hms.security.EncryptionKeyHolder.class, InsurerClaimsPostgresIT.Beans.class,
    InsurerClaimAccess.class, InsurerClaimLineReader.class, InsurerClaimLedger.class, InsurerClaimDraftSync.class,
    InsurerClaimBatchService.class, InsurerClaimSettlementService.class, InsurerClaimReportService.class,
    PdfClaimBordereauService.class, InvoiceCoverageService.class})
@org.junit.jupiter.api.Timeout(300)
class InsurerClaimsPostgresIT {

    @TestConfiguration
    static class Beans {
        @Bean
        Clock clock() {
            return Clock.systemDefaultZone();
        }

        @Bean
        MessageSource messageSource() {
            return TestMessageSources.bundles();
        }
    }

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_insurer_claims")
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

    private static final LocalDate NOVEMBER = LocalDate.of(2026, 11, 10);
    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 20);
    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 30);

    @MockitoBean private RoleValidator roles;
    @MockitoBean private ActingScopeResolver scope;
    @MockitoBean private AuditEventLogService audit;

    @Autowired private InsurerClaimBatchService batches;
    @Autowired private InsurerClaimSettlementService settlement;
    @Autowired private InsurerClaimReportService reports;
    @Autowired private InvoiceCoverageService coverage;
    @Autowired private BillingInvoiceRepository invoices;
    @Autowired private InvoicePayerShareRepository shares;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private TransactionTemplate tx;
    private UUID hospitalA;
    private UUID hospitalB;
    private UUID desk;
    private UUID accountant;
    private UUID admin;
    private UUID payerP;
    private UUID planP;
    private UUID payerQ;
    private UUID planQ;

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        for (String table : List.of("billing.billing_invoices", "billing.invoice_items", "billing.invoice_payer_shares",
                "billing.payers", "billing.payer_plans", "billing.payer_plan_terms", "clinical.patient_insurances",
                "clinical.patients", "security.user_role_hospital_assignment", "billing.insurer_claim_batches",
                "billing.insurer_claim_lines", "billing.insurer_payments", "billing.insurer_payment_allocations",
                "billing.insurer_claim_refusals", "billing.insurer_claim_write_offs",
                "billing.invoice_payer_share_lines")) {
            jdbc.execute("ALTER TABLE " + table + " DISABLE TRIGGER ALL");
        }
        tx = new TransactionTemplate(transactions);
        hospitalA = hospital();
        hospitalB = hospital();
        accountant = UUID.randomUUID();
        admin = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        jdbc.update("INSERT INTO security.roles (id, code, name, created_at, updated_at) VALUES (?, ?, ?, now(), now())",
            role, "ROLE_C_" + role.toString().substring(0, 8), "Caisse " + role.toString().substring(0, 8));
        desk = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO security.user_role_hospital_assignment (id, user_id, role_id, hospital_id, is_active,
                assigned_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, true, now(), now(), now())
            """, desk, UUID.randomUUID(), role, hospitalA);
        payerP = payer(hospitalA, "Mutuelle P");
        planP = plan(hospitalA, payerP);
        payerQ = payer(hospitalA, "Assureur Q");
        planQ = plan(hospitalA, payerQ);
        when(roles.canDecidePayerShare(any(), any())).thenReturn(true);
        when(roles.canSettleInsurerClaims(any(), any())).thenReturn(true);
        actAs(accountant, hospitalA);
    }

    private void actAs(UUID user, UUID hospital) {
        when(roles.getCurrentUserId()).thenReturn(user);
        when(scope.requirePinned()).thenReturn(hospital);
        when(roles.requireActiveHospitalId()).thenReturn(hospital);
    }

    private UUID hospital() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO hospital.hospitals (id, name, code, active, created_at, updated_at)"
            + " VALUES (?, ?, ?, TRUE, now(), now())", id, "Clinique " + id.toString().substring(0, 6),
            "ICL-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID payer(UUID hospital, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO billing.payers (id, hospital_id, code, name, payer_type) VALUES (?, ?, ?, ?, 'MUTUELLE')",
            id, hospital, "P" + id.toString().substring(0, 6).toUpperCase(), name);
        return id;
    }

    /** A plan at 80 % with no ceiling. */
    private UUID plan(UUID hospital, UUID payer) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO billing.payer_plans (id, hospital_id, payer_id, code, name, effective_from)
            VALUES (?, ?, ?, 'BASE', 'Formule Base', DATE '2026-01-01')
            """, id, hospital, payer);
        jdbc.update("""
            INSERT INTO billing.payer_plan_terms (hospital_id, plan_id, default_coverage_percent, effective_from)
            VALUES (?, ?, 80, DATE '2026-01-01')
            """, hospital, id);
        return id;
    }

    /** One invoice of a new insured patient, issued: its payer share is 80 % of each line. */
    private record Issued(UUID invoice, UUID patient, UUID insurance, UUID share) {
    }

    private Issued issued(String lastName, UUID plan, LocalDate day, String... linePrices) {
        UUID patient = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO clinical.patients (id, user_id, first_name, last_name, date_of_birth, phone_number_primary,
                is_active, chart_restricted, created_at, updated_at)
            VALUES (?, ?, 'Awa', ?, DATE '1990-06-15', ?, true, false, now(), now())
            """, patient, UUID.randomUUID(), lastName, "+226" + Math.abs(patient.hashCode()));
        UUID insurance = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO clinical.patient_insurances (id, patient_id, assignment_id, provider_name, policy_number,
                is_primary, payer_plan_id, effective_date, created_at, updated_at)
            VALUES (?, ?, ?, 'P', ?, true, ?, DATE '2026-01-01', now(), now())
            """, insurance, patient, desk, "POL-" + lastName, plan);
        UUID invoice = UUID.randomUUID();
        BigDecimal total = BigDecimal.ZERO;
        for (String price : linePrices) {
            total = total.add(new BigDecimal(price));
        }
        jdbc.update("""
            INSERT INTO billing.billing_invoices (id, patient_id, hospital_id, invoice_number, invoice_date, due_date,
                total_amount, amount_paid, status, check_in_copay, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'DRAFT', false, now(), now())
            """, invoice, patient, hospitalA, "F-" + invoice.toString().substring(0, 8), day, day.plusDays(30), total);
        int minute = 1;
        for (String price : linePrices) {
            jdbc.update("""
                INSERT INTO billing.invoice_items (id, billing_invoice_id, item_description, quantity, item_category,
                    unit_price, total_price, assignment_id, price_source, coverage_category, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, 1, 'SERVICE', ?, ?, ?, 'MANUAL', 'CONSULTATION',
                    TIMESTAMP '2026-11-10 09:00' + make_interval(mins => ?), now())
                """, invoice, "Acte " + minute, new BigDecimal(price), new BigDecimal(price), desk, minute++);
        }
        tx.executeWithoutResult(status -> {
            BillingInvoice entity = invoices.findLockedById(invoice).orElseThrow();
            entity.setStatus(InvoiceStatus.SENT);
            coverage.applyAtIssue(entity, accountant);
            entity.setStatus(InvoiceBalance.settledStatusOf(entity));
            invoices.save(entity);
        });
        UUID share = jdbc.queryForObject("SELECT id FROM billing.invoice_payer_shares WHERE invoice_id = ?",
            UUID.class, invoice);
        return new Issued(invoice, patient, insurance, share);
    }

    private InsurerClaimBatchDTO draft(UUID payer) {
        return batches.create(InsurerClaimBatchRequestDTO.builder().payerId(payer).periodFrom(FROM).periodTo(TO)
            .clientRequestId(UUID.randomUUID()).build());
    }

    private InsurerClaimBatchDTO sent(UUID payer) {
        InsurerClaimBatchDTO draft = draft(payer);
        return batches.submit(draft.getId(),
            InsurerClaimBatchSubmitRequestDTO.builder().submittedOn(LocalDate.now().minusDays(40)).build());
    }

    private InsurerPaymentRequestDTO payment(String amount, String reference, InsurerPaymentAllocationRequestDTO... on) {
        return InsurerPaymentRequestDTO.builder().payerId(payerP).amount(new BigDecimal(amount))
            .receivedDate(LocalDate.now()).method(InsurerPaymentMethod.BANK_TRANSFER).reference(reference)
            .allocations(List.of(on)).clientRequestId(UUID.randomUUID()).build();
    }

    private static InsurerPaymentAllocationRequestDTO on(UUID line, String amount) {
        return new InsurerPaymentAllocationRequestDTO(line, new BigDecimal(amount));
    }

    private InsurerClaimRefusalRequestDTO refusal(String amount, ClaimRefusalClass reasonClass,
                                                  ClaimRefusalResolution resolution) {
        return InsurerClaimRefusalRequestDTO.builder().amount(new BigDecimal(amount)).reasonClass(reasonClass)
            .reason("Motif de l'organisme").resolution(resolution)
            .writeOffReason(resolution == ClaimRefusalResolution.WRITE_OFF_REQUEST
                ? ClaimWriteOffReason.CONTRACTUAL_ADJUSTMENT : null)
            .supportingDocument(resolution == ClaimRefusalResolution.WRITE_OFF_REQUEST ? "Courrier du 12/11" : null)
            .clientRequestId(UUID.randomUUID()).build();
    }

    private BigDecimal money(String sql, Object... args) {
        return jdbc.queryForObject(sql, BigDecimal.class, args);
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    /* ---------------------------------------------------------------- tests */

    @Test
    @DisplayName("a November draft for payer P proposes exactly P's open November shares; sent, it is numbered, "
        + "frozen and reprints the same; a share is on one bordereau at a time; cancelled, its shares are open again")
    void theBordereauFromDraftToSent() {
        Issued sawadogo = issued("Sawadogo", planP, NOVEMBER, "2000", "5000");
        Issued kabore = issued("Kabore", planP, NOVEMBER, "10000");
        jdbc.update("UPDATE clinical.patients SET first_name = '=CMD|calc' WHERE id = ?", kabore.patient());
        Issued october = issued("Octobre", planP, OCTOBER, "3000");
        issued("AutrePayeur", planQ, NOVEMBER, "4000");

        InsurerClaimBatchDTO draft = draft(payerP);
        assertThat(draft.getStatus()).isEqualTo("DRAFT");
        assertThat(draft.getNumber()).isNull();
        assertThat(draft.getLines()).extracting(InsurerClaimLineDTO::getShareId)
            .containsOnly(sawadogo.share(), kabore.share()).hasSize(3);
        assertThat(draft.getClaimedTotal()).isEqualByComparingTo("13600");
        // The coverage was never verified: a warning, which does not stop the sending.
        assertThat(draft.getWarningCount()).isPositive();

        // A share is on one bordereau at a time: a second draft finds nothing, and adding it by hand is refused.
        InsurerClaimBatchDTO second = draft(payerP);
        assertThat(second.getLines()).isEmpty();
        UUID secondId = second.getId();
        InsurerClaimBatchSharesRequestDTO add = InsurerClaimBatchSharesRequestDTO.builder()
            .add(List.of(sawadogo.share())).build();
        assertThatThrownBy(() -> batches.changeShares(secondId, add))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                refused -> assertThat(refused.getFieldErrors()).containsOnlyKeys("add"));
        // The October share of the same payer can be added by hand (flagged out of the period).
        InsurerClaimBatchDTO withOctober = batches.changeShares(secondId,
            InsurerClaimBatchSharesRequestDTO.builder().add(List.of(october.share())).build());
        assertThat(withOctober.getLines()).singleElement()
            .satisfies(line -> assertThat(line.getWarnings()).contains("OUT_OF_PERIOD"));
        batches.deleteDraft(secondId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing.insurer_claim_lines WHERE batch_id = ?",
            Integer.class, secondId)).isZero();

        // The documents sent with an act are typed on the draft and read back; a card number is never one.
        UUID documented = draft.getLines().get(0).getId();
        assertThat(batches.setLineDocuments(draft.getId(), documented, " Ordonnance, compte rendu ").getLines())
            .filteredOn(line -> line.getId().equals(documented)).singleElement()
            .satisfies(line -> assertThat(line.getAttachedDocuments()).isEqualTo("Ordonnance, compte rendu"));
        assertThatThrownBy(() -> batches.setLineDocuments(draft.getId(), documented, "4111 1111 1111 1111"))
            .isInstanceOf(FieldBusinessException.class);

        InsurerClaimBatchDTO submitted = batches.submit(draft.getId(), new InsurerClaimBatchSubmitRequestDTO());
        assertThat(submitted.getLines()).filteredOn(line -> line.getId().equals(documented)).singleElement()
            .satisfies(line -> assertThat(line.getAttachedDocuments()).isEqualTo("Ordonnance, compte rendu"));
        // The day the payer received the file is kept apart from the sending date, never before it, never to come.
        com.example.hms.payload.dto.billing.InsurerClaimBatchReceiptRequestDTO early =
            new com.example.hms.payload.dto.billing.InsurerClaimBatchReceiptRequestDTO(LocalDate.now().minusDays(1),
                null);
        assertThatThrownBy(() -> batches.recordPayerReceipt(draft.getId(), early))
            .isInstanceOf(FieldBusinessException.class);
        assertThat(batches.recordPayerReceipt(draft.getId(),
            new com.example.hms.payload.dto.billing.InsurerClaimBatchReceiptRequestDTO(LocalDate.now(), "REC-981"))
            .getPayerReceivedOn()).isEqualTo(LocalDate.now());
        assertThat(batches.get(draft.getId()).getPayerReference()).isEqualTo("REC-981");
        assertThat(submitted.getStatus()).isEqualTo("SUBMITTED");
        assertThat(submitted.getNumber()).isEqualTo("BORD-" + LocalDate.now().getYear() + "-0001");
        assertThat(submitted.getGrossTotal()).isEqualByComparingTo("17000");
        assertThat(submitted.getPatientTotal()).isEqualByComparingTo("3400");
        assertThat(submitted.getClaimedTotal()).isEqualByComparingTo("13600");
        // The totals are the sum of the lines and the sum of the shares.
        assertThat(money("SELECT sum(claimed_amount) FROM billing.insurer_claim_lines WHERE batch_id = ?",
            draft.getId())).isEqualByComparingTo("13600");
        assertThat(money("SELECT sum(amount) FROM billing.invoice_payer_shares WHERE id IN (?, ?)", sawadogo.share(),
            kabore.share())).isEqualByComparingTo("13600");
        assertThat(text("SELECT status FROM billing.invoice_payer_shares WHERE id = ?", sawadogo.share()))
            .isEqualTo("CLAIMED");
        // QA D3983: a claimed share is refused on its bordereau, never wholly from the share list; the database
        // update itself only refuses an open share, so the patient's balance never moves from here.
        assertThatThrownBy(() -> coverage.reject(sawadogo.invoice(), sawadogo.share(), "Hors délai"))
            .isInstanceOf(ConflictException.class);
        Integer rejectedWhole = tx.execute(status -> shares.rejectWhole(sawadogo.share(), hospitalA, accountant,
            java.time.LocalDateTime.now(), "Hors délai"));
        assertThat(rejectedWhole).isZero();
        assertThat(text("SELECT status FROM billing.invoice_payer_shares WHERE id = ?", sawadogo.share()))
            .isEqualTo("CLAIMED");
        // Sent again (a retry): the same bordereau, the same number, nothing claimed twice.
        assertThat(batches.submit(draft.getId(), null).getNumber()).isEqualTo(submitted.getNumber());

        // A rename and a policy edit after the sending change nothing on the bordereau.
        jdbc.update("UPDATE clinical.patients SET last_name = 'Renomme' WHERE id = ?", sawadogo.patient());
        jdbc.update("UPDATE clinical.patient_insurances SET policy_number = 'CHANGED' WHERE id = ?",
            sawadogo.insurance());
        InsurerClaimBatchDTO reread = batches.get(draft.getId());
        assertThat(reread.getLines()).extracting(InsurerClaimLineDTO::getPatientName)
            .contains("Awa Sawadogo").doesNotContain("Awa Renomme");
        assertThat(reread.getLines()).extracting(InsurerClaimLineDTO::getPolicyNumber).doesNotContain("CHANGED");

        // The CSV and the PDF: the same lines and totals; a name that starts like a formula is neutralised.
        String csv = new String(reports.bordereauCsv(draft.getId(), Locale.FRENCH).content(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿").contains("\"'=CMD|calc").doesNotContain(";\"=CMD");
        assertThat(csv.split("\r\n")).hasSize(1 + 3 + 1);
        assertThat(csv).contains("\"TOTAL\"").contains(";17000;3400;13600;");
        assertThat(new String(reports.bordereauPdf(draft.getId(), Locale.FRENCH).content(), 0, 4,
            StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");

        // The database itself refuses a second live line for the same act, and a line closed above its claim.
        UUID lineId = submitted.getLines().get(0).getId();
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO billing.insurer_claim_lines (hospital_id, batch_id, share_id, invoice_id, invoice_item_id,
                claimed_amount)
            SELECT hospital_id, batch_id, share_id, invoice_id, invoice_item_id, claimed_amount
              FROM billing.insurer_claim_lines WHERE id = ?
            """, lineId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
            "UPDATE billing.insurer_claim_lines SET paid_amount = claimed_amount + 1 WHERE id = ?", lineId))
            .isInstanceOf(DataIntegrityViolationException.class);

        // Cancelled with a reason while the payer has not answered: the shares are open again and claimable.
        assertThatThrownBy(() -> batches.cancel(draft.getId(), " ")).isInstanceOf(FieldBusinessException.class);
        assertThat(batches.cancel(draft.getId(), "Envoyé au mauvais organisme").getStatus()).isEqualTo("CANCELLED");
        assertThat(text("SELECT status FROM billing.invoice_payer_shares WHERE id = ?", sawadogo.share()))
            .isEqualTo("OPEN");
        assertThat(draft(payerP).getLines()).hasSize(3);
    }

    @Test
    @DisplayName("QA r77: a share of 11 800, the payer pays 9 000 with a date and a reference, refuses 2 800 for a "
        + "patient-side reason: the patient's balance rises by exactly 2 800; a retry records one payment; the "
        + "payment is reversed only by another person")
    void aPartialPaymentThenARebilledRefusal() {
        Issued invoice = issued("Ouedraogo", planP, NOVEMBER, "14750");
        InsurerClaimBatchDTO batch = sent(payerP);
        UUID line = batch.getLines().get(0).getId();
        BigDecimal dueBefore = tx.execute(s -> InvoiceBalance.dueOf(invoices.findById(invoice.invoice()).orElseThrow()));
        assertThat(dueBefore).isEqualByComparingTo("2950");

        InsurerPaymentRequestDTO request = payment("9000", "VIR-2026-0042", on(line, "9000"));
        InsurerPaymentDTO paid = settlement.recordPayment(request);
        assertThat(settlement.recordPayment(request).getId()).as("the same key answers with the same payment")
            .isEqualTo(paid.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing.insurer_payments WHERE hospital_id = ?",
            Integer.class, hospitalA)).isEqualTo(1);
        assertThat(paid.getUnallocatedAmount()).isEqualByComparingTo("0");
        assertThat(text("SELECT status FROM billing.invoice_payer_shares WHERE id = ?", invoice.share()))
            .isEqualTo("PARTIALLY_PAID");
        assertThat(batches.get(batch.getId()).getOutstandingTotal()).isEqualByComparingTo("2800");
        // The same reference under another key is the same remittance: refused.
        assertThatThrownBy(() -> settlement.recordPayment(payment("9000", "vir-2026-0042")))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                refused -> assertThat(refused.getFieldErrors()).containsOnlyKeys("reference"));
        // No more than the line still expects, and a card number is never a reference.
        assertThatThrownBy(() -> settlement.recordPayment(payment("5000", "VIR-2", on(line, "2801"))))
            .isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> settlement.recordPayment(payment("100", "4111 1111 1111 1111")))
            .isInstanceOf(FieldBusinessException.class);

        // A provider fault is never the patient's debt; a patient-side reason can be, for exactly the amount.
        assertThatThrownBy(() -> settlement.refuse(line,
            refusal("2800", ClaimRefusalClass.PROVIDER_FAULT, ClaimRefusalResolution.REBILL_PATIENT)))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                refused -> assertThat(refused.getFieldErrors()).containsOnlyKeys("resolution"));
        InsurerClaimBatchDTO after = settlement.refuse(line,
            refusal("2800", ClaimRefusalClass.PATIENT_SIDE, ClaimRefusalResolution.REBILL_PATIENT));
        assertThat(after.getStatus()).isEqualTo("SETTLED");
        assertThat(after.getLines().get(0).getRefusals()).singleElement()
            .satisfies(row -> assertThat(row.getResolution()).isEqualTo("REBILL_PATIENT"));
        BigDecimal dueAfter = tx.execute(s -> InvoiceBalance.dueOf(invoices.findById(invoice.invoice()).orElseThrow()));
        assertThat(dueAfter.subtract(dueBefore)).isEqualByComparingTo("2800");
        assertThat(money("SELECT payer_share_total FROM billing.billing_invoices WHERE id = ?", invoice.invoice()))
            .isEqualByComparingTo("9000");
        assertThat(money("SELECT sum(payer_share_amount) FROM billing.invoice_items WHERE billing_invoice_id = ?",
            invoice.invoice())).as("the lines still add up to the invoice's payer share").isEqualByComparingTo("9000");
        assertThat(money("SELECT total_amount FROM billing.billing_invoices WHERE id = ?", invoice.invoice()))
            .as("the invoice total never moves").isEqualByComparingTo("14750");
        assertThat(text("SELECT status FROM billing.invoice_payer_shares WHERE id = ?", invoice.share()))
            .isEqualTo("PAID");
        // The refused amount is available again under the beneficiary's annual ceiling.
        assertThat(shares.sumUsedInYear(invoice.insurance(), hospitalA, 2026, UUID.randomUUID()))
            .isEqualByComparingTo("9000");

        // Reversed by another hospital admin or accountant, never by the person who recorded it.
        assertThatThrownBy(() -> settlement.reversePayment(paid.getId(), "Erreur de saisie"))
            .isInstanceOf(ConflictException.class);
        actAs(admin, hospitalA);
        assertThat(settlement.reversePayment(paid.getId(), "Erreur de saisie").getStatus()).isEqualTo("REVERSED");
        assertThat(text("SELECT status FROM billing.invoice_payer_shares WHERE id = ?", invoice.share()))
            .isEqualTo("CLAIMED");
        InsurerClaimBatchDTO reopened = batches.get(batch.getId());
        assertThat(reopened.getStatus()).isEqualTo("PARTIALLY_SETTLED");
        assertThat(reopened.getPaidTotal()).isEqualByComparingTo("0");
        assertThat(reopened.getOutstandingTotal()).isEqualByComparingTo("9000");
        assertThat(money("SELECT amount_paid FROM billing.billing_invoices WHERE id = ?", invoice.invoice()))
            .as("an insurer payment never touches what the patient paid").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a write-off is asked by one person and approved by another; it never raises what the patient owes; "
        + "another hospital finds nothing")
    void writeOffsAreFourEyesAndTenancyHolds() {
        Issued invoice = issued("Traore", planP, NOVEMBER, "10000");
        InsurerClaimBatchDTO batch = sent(payerP);
        UUID line = batch.getLines().get(0).getId();

        InsurerClaimWriteOffRequestDTO ask = InsurerClaimWriteOffRequestDTO.builder().amount(new BigDecimal("3000"))
            .reasonCode(ClaimWriteOffReason.COMMERCIAL_WAIVER).reason("Accord amiable").supportingDocument("Lettre 14")
            .clientRequestId(UUID.randomUUID()).build();
        InsurerClaimWriteOffDTO asked = settlement.requestWriteOff(line, ask);
        assertThat(settlement.requestWriteOff(line, ask).getId()).isEqualTo(asked.getId());
        assertThat(asked.getStatus()).isEqualTo("REQUESTED");
        // Held: the line takes at most 5 000 more while the request waits.
        assertThatThrownBy(() -> settlement.recordPayment(payment("8000", "VIR-9", on(line, "5001"))))
            .isInstanceOf(FieldBusinessException.class);
        assertThatThrownBy(() -> settlement.approveWriteOff(asked.getId())).isInstanceOf(ConflictException.class);

        // Another hospital: the line, the bordereau, the write-off and the reports hold nothing of hospital A.
        actAs(admin, hospitalB);
        assertThatThrownBy(() -> batches.get(batch.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> settlement.approveWriteOff(asked.getId()))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> settlement.refuse(line,
            refusal("100", ClaimRefusalClass.DISPUTED, ClaimRefusalResolution.CONTEST)))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(batches.list(null, null, null, null).content()).isEmpty();
        assertThat(settlement.listWriteOffs(null)).isEmpty();
        assertThat(reports.ageing(null).rows()).isEmpty();

        actAs(admin, hospitalA);
        assertThat(settlement.listWriteOffs(null)).singleElement()
            .satisfies(row -> assertThat(row.isRequestedByMe()).isFalse());
        assertThat(settlement.approveWriteOff(asked.getId()).getStatus()).isEqualTo("APPROVED");
        assertThat(money("SELECT written_off_amount FROM billing.invoice_payer_shares WHERE id = ?", invoice.share()))
            .isEqualByComparingTo("3000");
        assertThat(money("SELECT payer_share_total FROM billing.billing_invoices WHERE id = ?", invoice.invoice()))
            .as("a write-off is never the patient's debt").isEqualByComparingTo("8000");
        assertThat(shares.sumUsedInYear(invoice.insurance(), hospitalA, 2026, UUID.randomUUID()))
            .isEqualByComparingTo("5000");

        // A role that settles nothing is refused before anything is read.
        when(roles.canSettleInsurerClaims(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> settlement.recordPayment(payment("100", "VIR-X")))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> settlement.refuse(line,
            refusal("100", ClaimRefusalClass.PATIENT_SIDE, ClaimRefusalResolution.REBILL_PATIENT)))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
    }

    @Test
    @DisplayName("two people allocating to the same line at the same moment cannot overpay it")
    void concurrentAllocationsNeverOverpay() throws Exception {
        issued("Zongo", planP, NOVEMBER, "5000");
        UUID line = sent(payerP).getLines().get(0).getId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String reference = "VIR-C" + i;
            Callable<Boolean> attempt = () -> {
                LocaleContextHolder.setLocale(Locale.FRENCH);
                start.await();
                try {
                    settlement.recordPayment(payment("3000", reference, on(line, "3000")));
                    return true;
                } catch (FieldBusinessException | ConflictException refused) {
                    return false;
                }
            };
            results.add(pool.submit(attempt));
        }
        start.countDown();
        int won = 0;
        for (Future<Boolean> result : results) {
            won += Boolean.TRUE.equals(result.get(60, TimeUnit.SECONDS)) ? 1 : 0;
        }
        pool.shutdownNow();
        assertThat(won).isEqualTo(1);
        assertThat(money("SELECT paid_amount FROM billing.insurer_claim_lines WHERE id = ?", line))
            .isEqualByComparingTo("3000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing.insurer_payments WHERE hospital_id = ?",
            Integer.class, hospitalA)).isEqualTo(1);
    }

    @Test
    @DisplayName("money: after every step of a random run of payments, refusals, write-offs and reversals, the "
        + "shares' totals = claimed = paid + re-billed + written off + outstanding, and the reports add up")
    void everyAmountReconciles() {
        List<Issued> all = List.of(issued("Alpha", planP, NOVEMBER, "2000", "5000", "12500"),
            issued("Beta", planP, NOVEMBER, "10000", "7500"), issued("Gamma", planP, NOVEMBER, "30000"));
        BigDecimal invoiceTotals = money("SELECT sum(total_amount) FROM billing.billing_invoices WHERE hospital_id = ?",
            hospitalA);
        InsurerClaimBatchDTO batch = sent(payerP);
        List<UUID> lines = batch.getLines().stream().map(InsurerClaimLineDTO::getId).toList();
        BigDecimal claimed = batch.getClaimedTotal();
        assertThat(claimed).isEqualByComparingTo("53600");
        Random random = new Random(20261127L);
        List<UUID> payments = new ArrayList<>();
        List<UUID> writeOffs = new ArrayList<>();
        for (int step = 0; step < 60; step++) {
            UUID line = lines.get(random.nextInt(lines.size()));
            BigDecimal available = money("""
                SELECT claimed_amount - paid_amount - rebilled_amount - written_off_amount - write_off_pending_amount
                  FROM billing.insurer_claim_lines WHERE id = ?
                """, line);
            String amount = String.valueOf(1 + random.nextInt(4000));
            boolean fits = new BigDecimal(amount).compareTo(available) <= 0;
            int action = random.nextInt(6);
            try {
                actAs(accountant, hospitalA);
                switch (action) {
                    case 0, 1 -> payments.add(settlement.recordPayment(
                        payment(String.valueOf(Integer.parseInt(amount) + random.nextInt(500)), "VIR-R" + step,
                            on(line, amount))).getId());
                    case 2 -> settlement.refuse(line,
                        refusal(amount, ClaimRefusalClass.PATIENT_SIDE, ClaimRefusalResolution.REBILL_PATIENT));
                    case 3 -> settlement.refuse(line,
                        refusal(amount, ClaimRefusalClass.TARIFF_REDUCTION, ClaimRefusalResolution.WRITE_OFF_REQUEST));
                    case 4 -> {
                        if (!writeOffs.isEmpty()) {
                            actAs(admin, hospitalA);
                            UUID id = writeOffs.remove(random.nextInt(writeOffs.size()));
                            if (random.nextBoolean()) {
                                settlement.approveWriteOff(id);
                            } else {
                                settlement.refuseWriteOff(id, "Pièce insuffisante");
                            }
                        }
                    }
                    default -> {
                        if (!payments.isEmpty()) {
                            actAs(admin, hospitalA);
                            settlement.reversePayment(payments.remove(random.nextInt(payments.size())), "Erreur");
                        }
                    }
                }
                if (action <= 3) {
                    assertThat(fits).as("step %s: an amount above what the line expects was accepted", step).isTrue();
                }
            } catch (FieldBusinessException refused) {
                assertThat(fits).as("step %s: an amount that fits was refused: %s", step, refused.getFieldErrors())
                    .isFalse();
            }
            writeOffs.clear();
            writeOffs.addAll(jdbc.queryForList(
                "SELECT id FROM billing.insurer_claim_write_offs WHERE status = 'REQUESTED' AND hospital_id = ?",
                UUID.class, hospitalA));
            reconciles(batch.getId(), claimed, all, invoiceTotals, step);
        }
        assertThat(money("SELECT sum(paid_amount + rebilled_amount + written_off_amount) FROM"
            + " billing.insurer_claim_lines WHERE hospital_id = ?", hospitalA)).as("the run moved money").isPositive();
    }

    /** The identities that must hold after any step. */
    private void reconciles(UUID batchId, BigDecimal claimed, List<Issued> all, BigDecimal invoiceTotals, int step) {
        String at = "step " + step;
        // Every line: nothing negative, never more closed than claimed (the CHECK would have refused it too).
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM billing.insurer_claim_lines
             WHERE hospital_id = ?
               AND (paid_amount < 0 OR rebilled_amount < 0 OR written_off_amount < 0 OR write_off_pending_amount < 0
                OR paid_amount + rebilled_amount + written_off_amount + write_off_pending_amount > claimed_amount)
            """, Integer.class, hospitalA)).as(at).isZero();
        InsurerClaimBatchDTO batch = batches.get(batchId);
        assertThat(batch.getClaimedTotal()).as(at).isEqualByComparingTo(claimed);
        assertThat(batch.getPaidTotal().add(batch.getRebilledTotal()).add(batch.getWrittenOffTotal())
            .add(batch.getOutstandingTotal())).as(at + ": claimed = paid + re-billed + written off + outstanding")
            .isEqualByComparingTo(claimed);
        for (Issued one : all) {
            // Each share agrees with its lines, amount by amount.
            assertThat(jdbc.queryForObject("""
                SELECT s.amount = l.claimed AND s.paid_amount = l.paid AND s.rejected_amount = l.rebilled
                       AND s.written_off_amount = l.written_off
                  FROM billing.invoice_payer_shares s,
                       (SELECT sum(claimed_amount) claimed, sum(paid_amount) paid, sum(rebilled_amount) rebilled,
                               sum(written_off_amount) written_off
                          FROM billing.insurer_claim_lines WHERE share_id = ? AND released = false) l
                 WHERE s.id = ?
                """, Boolean.class, one.share(), one.share())).as(at + ": the share agrees with its lines").isTrue();
            // The invoice's payer share is the share less what was re-billed, and its lines add up to it.
            assertThat(jdbc.queryForObject("""
                SELECT i.payer_share_total = s.amount - s.rejected_amount
                       AND i.payer_share_total = (SELECT sum(payer_share_amount) FROM billing.invoice_items
                                                   WHERE billing_invoice_id = i.id)
                  FROM billing.billing_invoices i, billing.invoice_payer_shares s
                 WHERE i.id = ? AND s.id = ?
                """, Boolean.class, one.invoice(), one.share())).as(at + ": the invoice agrees with its share")
                .isTrue();
        }
        // The shares' totals = claimed = paid + outstanding + written off + re-billed.
        assertThat(money("SELECT sum(amount) FROM billing.invoice_payer_shares WHERE hospital_id = ?", hospitalA))
            .as(at).isEqualByComparingTo(claimed);
        assertThat(money("""
            SELECT sum(paid_amount + rejected_amount + written_off_amount
                       + (amount - paid_amount - rejected_amount - written_off_amount))
              FROM billing.invoice_payer_shares WHERE hospital_id = ?
            """, hospitalA)).as(at).isEqualByComparingTo(claimed);
        // No invoice total and no patient payment moved.
        assertThat(money("SELECT sum(total_amount) FROM billing.billing_invoices WHERE hospital_id = ?", hospitalA))
            .as(at).isEqualByComparingTo(invoiceTotals);
        assertThat(money("SELECT sum(amount_paid) FROM billing.billing_invoices WHERE hospital_id = ?", hospitalA))
            .as(at).isEqualByComparingTo("0");
        // A payment never allocates more than it holds; what the lines were paid is what is allocated.
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM billing.insurer_payments p
             WHERE p.hospital_id = ?
               AND p.amount < (SELECT coalesce(sum(a.amount), 0) FROM billing.insurer_payment_allocations a
                                WHERE a.payment_id = p.id AND a.voided_at IS NULL)
            """, Integer.class, hospitalA)).as(at).isZero();
        assertThat(money("SELECT coalesce(sum(amount), 0) FROM billing.insurer_payment_allocations"
            + " WHERE voided_at IS NULL AND hospital_id = ?", hospitalA)).as(at)
            .isEqualByComparingTo(batch.getPaidTotal());
        // The reports: the buckets add up to the outstanding total; the reconciliation identity holds.
        actAs(accountant, hospitalA);
        InsurerReceivablesDTO.AgeingRow ageing = reports.ageing(null).total();
        assertThat(ageing.days0To30().add(ageing.days31To60()).add(ageing.days61To90()).add(ageing.over90Days()))
            .as(at).isEqualByComparingTo(ageing.outstanding());
        assertThat(ageing.outstanding()).as(at).isEqualByComparingTo(batch.getOutstandingTotal());
        assertThat(ageing.days31To60()).as(at + ": sent 40 days ago").isEqualByComparingTo(ageing.outstanding());
        InsurerReceivablesDTO.ReconciliationRow total = reports.reconciliation(null, null, null).total();
        assertThat(total.claimed()).as(at).isEqualByComparingTo(
            total.paid().add(total.rebilled()).add(total.writtenOff()).add(total.outstanding()));
        assertThat(total.received().subtract(total.unallocated())).as(at + ": received = allocated + to allocate")
            .isEqualByComparingTo(total.paid());
    }
}
