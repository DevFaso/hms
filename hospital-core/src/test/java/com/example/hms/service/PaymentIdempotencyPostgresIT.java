package com.example.hms.service;

import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.payload.dto.StaffPaymentRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacySaleRequestDTO;
import com.example.hms.payload.dto.pharmacy.SaleLineRequestDTO;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeTestSupport;
import com.example.hms.service.pharmacy.PharmacySaleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Billing plan PR 11 (QA D2430) on a real PostgreSQL built by Liquibase (V310): two cashier submits racing on one
 * key record one payment, whatever the writer (staff payment, counter sale); two racing submits with different keys
 * still meet the invoice lock (one succeeds, the one that would overpay is refused); without a key nothing changes;
 * the V310 indexes hold the key unique per hospital on all three money-in tables, and the V310 backfill names an
 * older payment row's hospital.
 *
 * <p>The check-in copay and the dispense checkout are serialised by the appointment and the dispense row locks taken
 * before their key lookup (unit-tested in ReceptionServiceImplTest and PharmacyPaymentServiceImplTest); their
 * tables' indexes are proven here.
 */
@Testcontainers
@SpringBootTest(classes = HmsApplication.class)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@DisplayName("PR 11 (QA D2430): a payment retried with its key is recorded once, on PostgreSQL")
@org.junit.jupiter.api.Timeout(300)
class PaymentIdempotencyPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_payment_idempotency")
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

    private static final int RACERS = 4;

    @Autowired private BillingInvoiceService invoiceService;
    @Autowired private PharmacySaleService saleService;
    @Autowired private BillingInvoiceRepository invoices;
    @Autowired private HospitalRepository hospitals;
    @Autowired private UserRepository users;
    @Autowired private PatientRepository patients;
    @Autowired private PharmacyRepository pharmacies;
    @Autowired private MedicationCatalogItemRepository catalog;
    @Autowired private com.example.hms.repository.pharmacy.InventoryItemRepository inventoryItems;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.example.hms.repository.RoleRepository roles;
    @Autowired private com.example.hms.repository.UserRoleHospitalAssignmentRepository assignments;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.example.hms.service.SmsService smsService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.example.hms.service.EmailService emailService;

    private Hospital hospital;
    private User cashier;
    private Patient patient;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        hospital = hospitals.saveAndFlush(Hospital.builder()
            .name("Clinique D2430 " + tag)
            .code("P" + tag)
            .city("Ouagadougou").state("Centre").country("Burkina Faso")
            .phoneNumber("+226-555-2430").email(tag.toLowerCase(Locale.ROOT) + "@hospital.test")
            .active(true)
            .build());
        User account = new User();
        account.setUsername("caisse." + tag.toLowerCase(Locale.ROOT));
        account.setPasswordHash("x");
        account.setFirstName("Awa");
        account.setLastName("Caisse");
        account.setPhoneNumber("+2267" + (1_000_000 + Math.abs(tag.hashCode() % 8_999_999)));
        cashier = users.saveAndFlush(account);
        // PR 10 review: money is taken only by someone who holds a till at the hospital (their session can be
        // closed), read on the assignment.
        com.example.hms.model.Role role = roles.findByCode("ROLE_BILLING_SPECIALIST").orElseGet(() ->
            roles.saveAndFlush(com.example.hms.model.Role.builder().name("BILLING_SPECIALIST")
                .code("ROLE_BILLING_SPECIALIST").description("ROLE_BILLING_SPECIALIST").build()));
        assignments.saveAndFlush(com.example.hms.model.UserRoleHospitalAssignment.builder()
            .assignmentCode("D2430-" + tag).description("PR 11 IT").user(cashier).hospital(hospital).role(role)
            .startDate(LocalDate.now()).assignedAt(java.time.LocalDateTime.now()).active(true).build());
        User patientAccount = new User();
        patientAccount.setUsername("patient." + tag.toLowerCase(Locale.ROOT));
        patientAccount.setPasswordHash("x");
        patientAccount.setFirstName("Ali");
        patientAccount.setLastName("Sawadogo");
        patientAccount.setPhoneNumber("+2266" + (1_000_000 + Math.abs(tag.hashCode() % 8_999_999)));
        patientAccount = users.saveAndFlush(patientAccount);
        Patient p = new Patient();
        p.setUser(patientAccount);
        p.setFirstName("Ali");
        p.setLastName("Sawadogo");
        p.setDateOfBirth(LocalDate.of(1990, 1, 1));
        p.setGender("MALE");
        p.setPhoneNumberPrimary(patientAccount.getPhoneNumber());
        p.setCity("Ouagadougou");
        p.setHospitalId(hospital.getId());
        patient = patients.saveAndFlush(p);
        actAsCashier();
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    /** The cashier's request scope, on whichever thread runs the request. */
    private void actAsCashier() {
        ActingScopeTestSupport.actingAt(cashier.getId(), hospital.getId());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(cashier, null, List.of()));
    }

    private BillingInvoice invoiceOf(String total) {
        LocalDate today = LocalDate.now();
        return invoices.saveAndFlush(BillingInvoice.builder()
            .patient(patient)
            .hospital(hospital)
            .invoiceNumber("D2430-" + UUID.randomUUID().toString().substring(0, 8))
            .invoiceDate(today)
            .dueDate(today.plusDays(30))
            .totalAmount(new BigDecimal(total))
            .amountPaid(BigDecimal.ZERO)
            .status(InvoiceStatus.SENT)
            .build());
    }

    private int paymentRows(UUID invoiceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM billing.payment_transactions WHERE invoice_id = ?",
            Integer.class, invoiceId);
    }

    /** Runs every call at the same instant, each on its own thread under the cashier's scope. */
    private <R> List<Future<R>> race(List<Callable<R>> calls) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<R>> results = new ArrayList<>();
        try {
            for (Callable<R> call : calls) {
                results.add(pool.submit(() -> {
                    actAsCashier();
                    start.await();
                    try {
                        return call.call();
                    } finally {
                        HospitalContextHolder.clear();
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static int succeeded(List<? extends Future<?>> results) {
        int ok = 0;
        for (Future<?> result : results) {
            try {
                result.get();
                ok++;
            } catch (Exception refused) {
                // counted as refused
            }
        }
        return ok;
    }

    @Test
    @DisplayName("staff payment: four submits of one key at once record one row, and every one answers the invoice")
    void staffPaymentRacingOnOneKeyIsRecordedOnce() throws Exception {
        BillingInvoice invoice = invoiceOf("20000");
        UUID key = UUID.randomUUID();
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            calls.add(() -> invoiceService.recordStaffPayment(invoice.getId(),
                new StaffPaymentRequestDTO(new BigDecimal("1000"), "CASH", null, null, null, key),
                cashier.getId(), Locale.FRENCH));
        }

        assertThat(succeeded(race(calls))).as("a replay answers, it is not refused").isEqualTo(RACERS);
        assertThat(paymentRows(invoice.getId())).isEqualTo(1);
        assertThat(invoices.findById(invoice.getId()).orElseThrow().getAmountPaid()).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject(
            "SELECT hospital_id FROM billing.payment_transactions WHERE client_request_id = ?", UUID.class, key))
            .isEqualTo(hospital.getId());
    }

    @Test
    @DisplayName("staff payment: two keys whose total passes the balance meet the invoice lock: one paid, one refused")
    void staffPaymentsWithTwoKeysStillMeetTheBalance() throws Exception {
        BillingInvoice invoice = invoiceOf("1000");
        List<Callable<Object>> calls = List.of(
            () -> invoiceService.recordStaffPayment(invoice.getId(),
                new StaffPaymentRequestDTO(new BigDecimal("600"), "CASH", null, null, null, UUID.randomUUID()),
                cashier.getId(), Locale.FRENCH),
            () -> invoiceService.recordStaffPayment(invoice.getId(),
                new StaffPaymentRequestDTO(new BigDecimal("600"), "CASH", null, null, null, UUID.randomUUID()),
                cashier.getId(), Locale.FRENCH));

        assertThat(succeeded(race(calls))).isEqualTo(1);
        assertThat(paymentRows(invoice.getId())).isEqualTo(1);
        assertThat(invoices.findById(invoice.getId()).orElseThrow().getAmountPaid()).isEqualByComparingTo("600");
    }

    @Test
    @DisplayName("staff payment: without a key, a second same payment is asked about (QA D2704), then two rows")
    void staffPaymentWithoutAKeyIsUnchanged() {
        BillingInvoice invoice = invoiceOf("5000");
        invoiceService.recordStaffPayment(invoice.getId(),
            new StaffPaymentRequestDTO(new BigDecimal("1000"), "CASH", null, null), cashier.getId(), Locale.FRENCH);
        UUID invoiceId = invoice.getId();
        UUID cashierId = cashier.getId();
        StaffPaymentRequestDTO again = new StaffPaymentRequestDTO(new BigDecimal("1000"), "CASH", null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                invoiceService.recordStaffPayment(invoiceId, again, cashierId, Locale.FRENCH))
            .isInstanceOf(com.example.hms.exception.FieldBusinessException.class);
        assertThat(paymentRows(invoice.getId())).isEqualTo(1);
        StaffPaymentRequestDTO confirmed = new StaffPaymentRequestDTO(new BigDecimal("1000"), "CASH", null, null,
            null, null, Boolean.TRUE);
        invoiceService.recordStaffPayment(invoiceId, confirmed, cashierId, Locale.FRENCH);
        assertThat(paymentRows(invoice.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("staff payment: the key of a payment on another invoice is refused, nothing recorded")
    void staffPaymentKeyOnAnotherInvoiceIsRefused() {
        BillingInvoice first = invoiceOf("5000");
        BillingInvoice second = invoiceOf("5000");
        UUID key = UUID.randomUUID();
        invoiceService.recordStaffPayment(first.getId(),
            new StaffPaymentRequestDTO(new BigDecimal("1000"), "CASH", null, null, null, key), cashier.getId(),
            Locale.FRENCH);
        StaffPaymentRequestDTO reused = new StaffPaymentRequestDTO(new BigDecimal("1000"), "CASH", null, null, null, key);
        UUID secondId = second.getId();
        UUID cashierId = cashier.getId();

        assertThatThrownBy(() -> invoiceService.recordStaffPayment(secondId, reused, cashierId, Locale.FRENCH))
            .isInstanceOf(com.example.hms.exception.ConflictException.class);
        assertThat(paymentRows(second.getId())).isZero();
    }

    @Test
    @DisplayName("counter sale: four submits of one key at once record one sale")
    void counterSaleRacingOnOneKeyIsRecordedOnce() throws Exception {
        Pharmacy pharmacy = pharmacies.saveAndFlush(Pharmacy.builder().hospital(hospital).name("Officine D2430").build());
        MedicationCatalogItem item = catalog.saveAndFlush(MedicationCatalogItem.builder()
            .nameFr("Paracétamol 500 mg").genericName("paracetamol").hospital(hospital).build());
        // QA D2671 / D2670 (r65): the sale is priced from the pharmacy's list and takes its stock.
        com.example.hms.model.pharmacy.InventoryItem stocked = inventoryItems.saveAndFlush(
            com.example.hms.model.pharmacy.InventoryItem.builder().pharmacy(pharmacy).medicationCatalogItem(item)
                .unit("comprimé").unitPrice(new BigDecimal("500")).quantityOnHand(new BigDecimal("100")).build());
        UUID key = UUID.randomUUID();
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            calls.add(() -> saleService.createSale(PharmacySaleRequestDTO.builder()
                .pharmacyId(pharmacy.getId())
                .hospitalId(hospital.getId())
                .paymentMethod(PharmacyPaymentMethod.CASH)
                .clientRequestId(key)
                .lines(List.of(SaleLineRequestDTO.builder()
                    .medicationCatalogItemId(item.getId())
                    .quantity(BigDecimal.ONE)
                    .unitPrice(new BigDecimal("500"))
                    .build()))
                .build()));
        }

        assertThat(succeeded(race(calls))).isEqualTo(RACERS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM clinical.pharmacy_sales WHERE client_request_id = ?",
            Integer.class, key)).isEqualTo(1);
        // The stock went down once, with one SALE movement.
        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM clinical.inventory_items WHERE id = ?",
            BigDecimal.class, stocked.getId())).isEqualByComparingTo("99");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM clinical.stock_transactions WHERE inventory_item_id = ?"
            + " AND transaction_type = 'SALE'", Integer.class, stocked.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("V310: the key is unique per hospital on the three money-in tables, and absent keys never clash")
    void theV310IndexesHoldTheKeyUniquePerHospital() {
        List<String> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE indexname IN ('uk_pt_hospital_client_request',"
                + " 'uk_pp_hospital_client_request', 'uk_psale_hospital_client_request')", String.class);
        assertThat(indexes).containsExactlyInAnyOrder("uk_pt_hospital_client_request",
            "uk_pp_hospital_client_request", "uk_psale_hospital_client_request");

        BillingInvoice invoice = invoiceOf("5000");
        UUID key = UUID.randomUUID();
        String insert = "INSERT INTO billing.payment_transactions (id, invoice_id, amount, payment_date, payment_method,"
            + " status, hospital_id, client_request_id, created_at, updated_at)"
            + " VALUES (?, ?, 100, CURRENT_DATE, 'CASH', 'CONFIRMED', ?, ?, now(), now())";
        jdbc.update(insert, UUID.randomUUID(), invoice.getId(), hospital.getId(), key);
        UUID invoiceId = invoice.getId();
        UUID hospitalId = hospital.getId();
        UUID secondRow = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(insert, secondRow, invoiceId, hospitalId, key))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("uk_pt_hospital_client_request");
        // No key: as many rows as before (the index is partial).
        jdbc.update(insert, UUID.randomUUID(), invoiceId, hospitalId, null);
        jdbc.update(insert, UUID.randomUUID(), invoiceId, hospitalId, null);
        assertThat(paymentRows(invoiceId)).isEqualTo(3);
    }

    @Test
    @DisplayName("V310: an older payment row with no hospital takes its invoice's hospital from the backfill")
    void theV310BackfillNamesTheInvoiceHospital() {
        BillingInvoice invoice = invoiceOf("5000");
        UUID row = UUID.randomUUID();
        jdbc.update("INSERT INTO billing.payment_transactions (id, invoice_id, amount, payment_date, payment_method,"
            + " status, created_at, updated_at) VALUES (?, ?, 100, CURRENT_DATE, 'CASH', 'CONFIRMED', now(), now())",
            row, invoice.getId());

        // The V310 backfill statement, as shipped (it is idempotent: it touches only rows still without one).
        jdbc.update("UPDATE billing.payment_transactions pt SET hospital_id = bi.hospital_id"
            + " FROM billing.billing_invoices bi WHERE bi.id = pt.invoice_id AND pt.hospital_id IS NULL");

        assertThat(jdbc.queryForObject("SELECT hospital_id FROM billing.payment_transactions WHERE id = ?",
            UUID.class, row)).isEqualTo(hospital.getId());
    }
}
