package com.example.hms.service;

import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
import com.example.hms.enums.CashTill;
import com.example.hms.enums.DispenseStatus;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.PaymentMethod;
import com.example.hms.enums.PaymentStatus;
import com.example.hms.enums.PharmacyPaymentMethod;
import com.example.hms.exception.ConflictException;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PaymentTransaction;
import com.example.hms.model.Prescription;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.payload.dto.StaffPaymentRequestDTO;
import com.example.hms.payload.dto.billing.CashSessionCloseRequestDTO;
import com.example.hms.payload.dto.billing.CashSessionDTO;
import com.example.hms.payload.dto.billing.InvoiceRefundDTO;
import com.example.hms.payload.dto.billing.InvoiceRefundRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyPaymentRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacySaleRequestDTO;
import com.example.hms.payload.dto.pharmacy.SaleLineRequestDTO;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PaymentTransactionRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.InventoryItemRepository;
import com.example.hms.repository.pharmacy.PharmacyRepository;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeTestSupport;
import com.example.hms.service.billing.InvoiceRefundService;
import com.example.hms.service.billing.cash.CashSessionService;
import com.example.hms.service.pharmacy.PharmacyPaymentService;
import com.example.hms.service.pharmacy.PharmacySaleService;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Billing plan PR 10 (V309) on a real PostgreSQL built by Liquibase, the two ITs the plan asked for:
 *
 * <ul>
 *   <li><b>The lock.</b> Once a cashier's session is closed, every money writer of that till is refused with the
 *   « caisse clôturée » conflict and writes nothing: the staff payment, the confirmation of a patient's
 *   declaration, a refund paid out, a reversal paid out (BILLING); the check-in copay (RECEPTION); the dispense
 *   payment and the counter sale (PHARMACY). A supervisor's reopen lets the same payment through.</li>
 *   <li><b>A cross-day reversal.</b> A payment taken and counted yesterday is reversed today: yesterday's closed
 *   session still shows the money in and still balances, today's session shows the money out, and the payment is
 *   REVERSED naming the refund that handed it back.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(classes = HmsApplication.class)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
@DisplayName("PR 10: a closed till takes no money, and a reversal lands on the day it is paid out (PostgreSQL)")
@org.junit.jupiter.api.Timeout(300)
class CashSessionLockPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_cash_sessions")
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

    private static final String CLOSED_TILL = "clôturée";

    @Autowired private BillingInvoiceService invoiceService;
    @Autowired private InvoiceRefundService refundService;
    @Autowired private CashSessionService cashSessions;
    @Autowired private PharmacySaleService saleService;
    @Autowired private PharmacyPaymentService pharmacyPaymentService;
    @Autowired private BillingInvoiceRepository invoices;
    @Autowired private PaymentTransactionRepository payments;
    @Autowired private HospitalRepository hospitals;
    @Autowired private UserRepository users;
    @Autowired private PatientRepository patients;
    @Autowired private RoleRepository roles;
    @Autowired private com.example.hms.repository.PatientHospitalRegistrationRepository registrations;
    @Autowired private UserRoleHospitalAssignmentRepository assignments;
    @Autowired private PharmacyRepository pharmacies;
    @Autowired private MedicationCatalogItemRepository catalog;
    @Autowired private InventoryItemRepository inventory;
    @Autowired private DispenseRepository dispenses;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.example.hms.service.SmsService smsService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.example.hms.service.EmailService emailService;

    private TransactionTemplate tx;
    private String tag;
    private Hospital hospital;
    private User cashier;
    private User accountant;
    private User receptionist;
    private User pharmacist;
    private Patient patient;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        tx = new TransactionTemplate(transactions);
        tag = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        hospital = hospitals.saveAndFlush(Hospital.builder()
            .name("Clinique Caisse " + tag)
            .code("C" + tag)
            .city("Ouagadougou").state("Centre").country("Burkina Faso")
            .phoneNumber("+226-555-0309").email(tag.toLowerCase(Locale.ROOT) + "@caisse.test")
            .active(true)
            .build());
        cashier = staff("caisse", 1, "ROLE_BILLING_SPECIALIST");
        accountant = staff("compta", 2, "ROLE_ACCOUNTANT");
        receptionist = staff("accueil", 3, "ROLE_RECEPTIONIST");
        pharmacist = staff("pharma", 4, "ROLE_PHARMACIST");
        User patientAccount = account("patient", 5);
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
        // A patient is read through their registration at the hospital (the pharmacy checkout does).
        registrations.saveAndFlush(com.example.hms.model.PatientHospitalRegistration.builder()
            .patient(patient).hospital(hospital).mrn("CS" + tag)
            .registrationDate(LocalDate.now()).active(true).build());
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
        SecurityContextHolder.clearContext();
        LocaleContextHolder.resetLocaleContext();
    }

    // ───────────────────────────── fixtures ─────────────────────────────

    private User account(String name, int rank) {
        User account = new User();
        account.setUsername(name + "." + tag.toLowerCase(Locale.ROOT));
        account.setPasswordHash("x");
        account.setFirstName("Awa");
        account.setLastName(name);
        account.setPhoneNumber("+226" + rank + (1_000_000 + Math.abs(tag.hashCode() % 8_999_999)));
        return users.saveAndFlush(account);
    }

    /** A user holding {@code roleCode} at the hospital: the till roles are read on the assignment. */
    private User staff(String name, int rank, String roleCode) {
        User user = account(name, rank);
        Role role = roles.findByCode(roleCode).orElseGet(() -> roles.saveAndFlush(
            Role.builder().name(roleCode.substring(5)).code(roleCode).description(roleCode).build()));
        assignments.saveAndFlush(UserRoleHospitalAssignment.builder()
            .assignmentCode("CS-" + tag + "-" + rank).description("PR 10 IT").user(user).hospital(hospital)
            .role(role).startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build());
        return user;
    }

    private void actAs(User user) {
        HospitalContextHolder.clear();
        ActingScopeTestSupport.actingAt(user.getId(), hospital.getId());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private BillingInvoice invoiceOf(String total, String paid, InvoiceStatus status) {
        LocalDate today = LocalDate.now();
        return invoices.saveAndFlush(BillingInvoice.builder()
            .patient(patient)
            .hospital(hospital)
            .invoiceNumber("CS-" + UUID.randomUUID().toString().substring(0, 8))
            .invoiceDate(today)
            .dueDate(today.plusDays(30))
            .totalAmount(new BigDecimal(total))
            .amountPaid(new BigDecimal(paid))
            .status(status)
            .build());
    }

    private PaymentTransaction paymentRow(BillingInvoice invoice, String amount, PaymentStatus status) {
        return payments.saveAndFlush(PaymentTransaction.builder()
            .invoice(invoice)
            .hospitalId(hospital.getId())
            .amount(new BigDecimal(amount))
            .paymentDate(LocalDate.now())
            .paymentMethod(PaymentMethod.CASH)
            .recordedBy(cashier.getId())
            .status(status)
            .build());
    }

    /** {@code user}'s session for {@code till} today, opened if needed and closed with what it expects. */
    private UUID closedTill(User user, CashTill till) {
        actAs(user);
        UUID id = tx.execute(status -> cashSessions.stamp(till, hospital.getId(), user.getId()));
        CashSessionDTO open = cashSessions.get(id);
        List<CashSessionCloseRequestDTO.CountedLine> counts = new ArrayList<>();
        open.getMethods().forEach(line -> counts.add(new CashSessionCloseRequestDTO.CountedLine(line.getMethod(),
            line.getOperator() == null ? null
                : com.example.hms.enums.MobileMoneyOperator.valueOf(line.getOperator()),
            line.getExpected(), null)));
        if (counts.isEmpty()) {
            counts.add(new CashSessionCloseRequestDTO.CountedLine("CASH", null, BigDecimal.ZERO, null));
        }
        assertThat(cashSessions.close(id, new CashSessionCloseRequestDTO(counts)).getStatus()).isEqualTo("CLOSED");
        return id;
    }

    private void refusedByTheClosedTill(String writer, ThrowingCallable call) {
        assertThatThrownBy(call).as(writer + " on a closed till")
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining(CLOSED_TILL);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private InvoiceRefundDTO approvedRefund(BillingInvoice overpaid, String amount) {
        InvoiceRefundRequestDTO body = new InvoiceRefundRequestDTO();
        body.setAmount(new BigDecimal(amount));
        body.setMethod("CASH");
        body.setReason("Trop-perçu");
        actAs(cashier);
        InvoiceRefundDTO asked = refundService.request(overpaid.getId(), body);
        actAs(accountant);
        return refundService.approve(overpaid.getId(), asked.getId());
    }

    private InvoiceRefundDTO approvedReversal(BillingInvoice invoice, PaymentTransaction payment) {
        actAs(cashier);
        InvoiceRefundDTO asked = refundService.requestReversal(invoice.getId(), payment.getId(), "Saisie en double");
        actAs(accountant);
        return refundService.approve(invoice.getId(), asked.getId());
    }

    // ───────────────────────────── the lock ─────────────────────────────

    @Test
    @DisplayName("BILLING: payment, declaration confirmed, refund and reversal paid out are refused once the till is closed")
    void theBillingTillTakesNoMoneyOnceClosed() {
        BillingInvoice toPay = invoiceOf("5000", "0", InvoiceStatus.SENT);
        BillingInvoice declared = invoiceOf("5000", "0", InvoiceStatus.SENT);
        PaymentTransaction declaration = paymentRow(declared, "1000", PaymentStatus.PENDING);
        BillingInvoice overpaid = invoiceOf("2000", "3000", InvoiceStatus.PAID);
        InvoiceRefundDTO refund = approvedRefund(overpaid, "1000");
        BillingInvoice paidInError = invoiceOf("5000", "1000", InvoiceStatus.PARTIALLY_PAID);
        PaymentTransaction mistaken = paymentRow(paidInError, "1000", PaymentStatus.CONFIRMED);
        InvoiceRefundDTO reversal = approvedReversal(paidInError, mistaken);

        UUID sessionId = closedTill(cashier, CashTill.BILLING);
        actAs(cashier);
        UUID cashierId = cashier.getId();

        refusedByTheClosedTill("a staff payment", () -> invoiceService.recordStaffPayment(toPay.getId(),
            new StaffPaymentRequestDTO(new BigDecimal("1000"), "CASH", null, null), cashierId, Locale.FRENCH));
        refusedByTheClosedTill("a declaration confirmed", () -> invoiceService.confirmPayment(declared.getId(),
            declaration.getId(), cashierId, Locale.FRENCH));
        refusedByTheClosedTill("a refund paid out", () -> refundService.pay(overpaid.getId(), refund.getId(), null));
        refusedByTheClosedTill("a reversal paid out",
            () -> refundService.pay(paidInError.getId(), reversal.getId(), null));

        // Nothing was written: no payment row, the declaration still waits, the refunds still wait, the money held
        // on each invoice is what it was.
        assertThat(count("SELECT COUNT(*) FROM billing.payment_transactions WHERE invoice_id = ?", toPay.getId()))
            .isZero();
        assertThat(invoices.findById(toPay.getId()).orElseThrow().getAmountPaid()).isEqualByComparingTo("0");
        assertThat(payments.findById(declaration.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(invoices.findById(declared.getId()).orElseThrow().getAmountPaid()).isEqualByComparingTo("0");
        assertThat(count("SELECT COUNT(*) FROM billing.invoice_refunds WHERE status = 'APPROVED' AND id IN (?, ?)",
            refund.getId(), reversal.getId())).isEqualTo(2);
        assertThat(invoices.findById(overpaid.getId()).orElseThrow().getAmountRefunded()).isEqualByComparingTo("0");
        assertThat(payments.findById(mistaken.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(count("SELECT COUNT(*) FROM billing.payment_transactions WHERE cash_session_id = ?", sessionId))
            .isZero();
        assertThat(count("SELECT COUNT(*) FROM billing.invoice_refunds WHERE cash_session_id = ?", sessionId))
            .isZero();

        // The cashier cannot reopen their own till; the accountant can, with a reason, and the payment goes in.
        actAs(accountant);
        assertThat(cashSessions.reopen(sessionId, "Paiement arrivé après la clôture").getStatus()).isEqualTo("OPEN");
        actAs(cashier);
        invoiceService.recordStaffPayment(toPay.getId(),
            new StaffPaymentRequestDTO(new BigDecimal("1000"), "CASH", null, null), cashierId, Locale.FRENCH);
        assertThat(count("SELECT COUNT(*) FROM billing.payment_transactions WHERE cash_session_id = ?", sessionId))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("RECEPTION: the check-in copay is refused once the desk's till is closed")
    void theReceptionTillTakesNoCopayOnceClosed() {
        closedTill(receptionist, CashTill.RECEPTION);
        actAs(receptionist);
        // The visit itself is not needed: its row is never read, and the refusal rolls the copay invoice back.
        Encounter visit = new Encounter();
        visit.setId(UUID.randomUUID());
        visit.setVersion(0L);
        visit.setHospital(hospital);
        visit.setPatient(patient);
        UUID receptionistId = receptionist.getId();

        refusedByTheClosedTill("a check-in copay", () -> tx.executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            invoiceService.recordCheckInCopay(visit, new BigDecimal("500"), PaymentMethod.CASH, null, null,
                receptionistId, null);
        }));

        assertThat(count("SELECT COUNT(*) FROM billing.billing_invoices WHERE encounter_id = ?", visit.getId()))
            .isZero();
        assertThat(count("SELECT COUNT(*) FROM billing.payment_transactions WHERE recorded_by = ?", receptionistId))
            .isZero();
    }

    @Test
    @DisplayName("PHARMACY: the counter sale and the dispense payment are refused once the pharmacist's till is closed")
    void thePharmacyTillTakesNoMoneyOnceClosed() {
        Pharmacy pharmacy = pharmacies.saveAndFlush(Pharmacy.builder().hospital(hospital).name("Officine " + tag)
            .build());
        MedicationCatalogItem item = catalog.saveAndFlush(MedicationCatalogItem.builder()
            .nameFr("Paracétamol 500 mg").genericName("paracetamol").hospital(hospital).build());
        inventory.saveAndFlush(InventoryItem.builder().pharmacy(pharmacy).medicationCatalogItem(item)
            .quantityOnHand(new BigDecimal("100")).unitPrice(new BigDecimal("50")).build());
        UUID dispenseId = tx.execute(status -> {
            // The prescription behind the fill is not needed: only the fill, its patient and its pharmacy are read.
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            return dispenses.saveAndFlush(Dispense.builder()
                .prescription(entityManager.getReference(Prescription.class, UUID.randomUUID()))
                .patient(patient)
                .pharmacy(pharmacy)
                .dispensedByUser(pharmacist)
                .medicationCatalogItem(item)
                .medicationName("Paracétamol 500 mg")
                .quantityRequested(new BigDecimal("10"))
                .quantityDispensed(new BigDecimal("10"))
                .status(DispenseStatus.COMPLETED)
                .build()).getId();
        });

        closedTill(pharmacist, CashTill.PHARMACY);
        actAs(pharmacist);

        refusedByTheClosedTill("a counter sale", () -> saleService.createSale(PharmacySaleRequestDTO.builder()
            .pharmacyId(pharmacy.getId())
            .hospitalId(hospital.getId())
            .paymentMethod(PharmacyPaymentMethod.CASH)
            .lines(List.of(SaleLineRequestDTO.builder()
                .medicationCatalogItemId(item.getId())
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("50"))
                .build()))
            .build()));
        refusedByTheClosedTill("a dispense payment", () -> pharmacyPaymentService.createPayment(
            PharmacyPaymentRequestDTO.builder()
                .dispenseId(dispenseId)
                .patientId(patient.getId())
                .hospitalId(hospital.getId())
                .paymentMethod(PharmacyPaymentMethod.CASH)
                .amount(new BigDecimal("500"))
                .build()));

        assertThat(count("SELECT COUNT(*) FROM clinical.pharmacy_sales WHERE hospital_id = ?", hospital.getId()))
            .isZero();
        assertThat(count("SELECT COUNT(*) FROM billing.pharmacy_payments WHERE dispense_id = ?", dispenseId)).isZero();
        // The stock a refused sale would have taken is still on the shelf.
        assertThat(inventory.findByPharmacyIdAndMedicationCatalogItemId(pharmacy.getId(), item.getId())
            .orElseThrow().getQuantityOnHand()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("a closed till of one cashier or one till never blocks another: the lock is per cashier, till and day")
    void theLockIsPerCashierAndTill() {
        BillingInvoice toPay = invoiceOf("5000", "0", InvoiceStatus.SENT);
        closedTill(receptionist, CashTill.RECEPTION);
        closedTill(accountant, CashTill.BILLING);

        actAs(cashier);
        invoiceService.recordStaffPayment(toPay.getId(),
            new StaffPaymentRequestDTO(new BigDecimal("1000"), "CASH", null, null), cashier.getId(), Locale.FRENCH);

        assertThat(invoices.findById(toPay.getId()).orElseThrow().getAmountPaid()).isEqualByComparingTo("1000");
    }

    // ───────────────────────────── a cross-day reversal ─────────────────────────────

    @Test
    @DisplayName("a payment counted yesterday and reversed today: yesterday still balances, today shows the money out")
    void aReversalPaidOutTheNextDayLeavesYesterdaysTillAsCounted() {
        BillingInvoice invoice = invoiceOf("5000", "0", InvoiceStatus.SENT);
        actAs(cashier);
        invoiceService.recordStaffPayment(invoice.getId(),
            new StaffPaymentRequestDTO(new BigDecimal("1000"), "CASH", null, null), cashier.getId(), Locale.FRENCH);
        PaymentTransaction payment = payments.findByInvoice_IdOrderByCreatedAtAsc(invoice.getId()).get(0);
        UUID yesterdaysSession = closedTill(cashier, CashTill.BILLING);
        assertThat(payment.getCashSessionId()).isEqualTo(yesterdaysSession);
        // That day is now yesterday: the session, its close and the payment all happened one day earlier.
        LocalDate today = jdbc.queryForObject("SELECT business_date FROM billing.cash_sessions WHERE id = ?",
            LocalDate.class, yesterdaysSession);
        LocalDate yesterday = today.minusDays(1);
        jdbc.update("""
            UPDATE billing.cash_sessions
               SET business_date = ?, opened_at = opened_at - INTERVAL '1 day', closed_at = closed_at - INTERVAL '1 day'
             WHERE id = ?
            """, yesterday, yesterdaysSession);
        jdbc.update("""
            UPDATE billing.payment_transactions
               SET payment_date = payment_date - 1, created_at = created_at - INTERVAL '1 day',
                   confirmed_at = confirmed_at - INTERVAL '1 day'
             WHERE id = ?
            """, payment.getId());

        // Today: asked by the cashier, approved by the accountant, handed back by the cashier.
        InvoiceRefundDTO reversal = approvedReversal(invoice, payment);
        actAs(cashier);
        InvoiceRefundDTO paid = refundService.pay(invoice.getId(), reversal.getId(), null);

        assertThat(paid.getStatus()).isEqualTo("PAID");
        UUID todaysSession = jdbc.queryForObject("SELECT cash_session_id FROM billing.invoice_refunds WHERE id = ?",
            UUID.class, reversal.getId());
        assertThat(todaysSession).as("the payout opened today's session, it did not touch yesterday's closed one")
            .isNotNull().isNotEqualTo(yesterdaysSession);
        assertThat(jdbc.queryForObject("SELECT business_date FROM billing.cash_sessions WHERE id = ?",
            LocalDate.class, todaysSession)).isEqualTo(today);
        PaymentTransaction reversed = payments.findById(payment.getId()).orElseThrow();
        assertThat(reversed.getStatus()).isEqualTo(PaymentStatus.REVERSED);
        assertThat(reversed.getReversalRefundId()).isEqualTo(reversal.getId());
        assertThat(reversed.getCashSessionId()).as("the payment stays in the session it came in").isEqualTo(
            yesterdaysSession);
        BillingInvoice after = invoices.findById(invoice.getId()).orElseThrow();
        assertThat(after.getAmountPaid()).isEqualByComparingTo("0");
        assertThat(after.getStatus()).isEqualTo(InvoiceStatus.SENT);

        // Yesterday as it was counted: 1 000 in, nothing out, counted 1 000, no difference, still closed.
        CashSessionDTO dayOne = cashSessions.get(yesterdaysSession);
        assertThat(dayOne.getStatus()).isEqualTo("CLOSED");
        assertThat(dayOne.getBusinessDate()).isEqualTo(yesterday);
        assertThat(dayOne.getTotalIn()).isEqualByComparingTo("1000");
        assertThat(dayOne.getTotalOut()).isEqualByComparingTo("0");
        assertThat(dayOne.getCashToCount()).isEqualByComparingTo("1000");
        assertThat(dayOne.getCashDifference()).isEqualByComparingTo("0");
        assertThat(dayOne.getDifferenceLines()).isZero();
        assertThat(dayOne.getMovements()).singleElement().satisfies(m -> {
            assertThat(m.getKind()).isEqualTo("PAYMENT");
            assertThat(m.getDirection()).isEqualTo("IN");
            assertThat(m.getStatus()).isEqualTo("REVERSED");
        });
        // Today: the money out, and nothing in.
        CashSessionDTO dayTwo = cashSessions.get(todaysSession);
        assertThat(dayTwo.getStatus()).isEqualTo("OPEN");
        assertThat(dayTwo.getTotalIn()).isEqualByComparingTo("0");
        assertThat(dayTwo.getTotalOut()).isEqualByComparingTo("1000");
        assertThat(dayTwo.getCashToCount()).isEqualByComparingTo("-1000");
        assertThat(dayTwo.getMovements()).singleElement().satisfies(m -> {
            assertThat(m.getKind()).isEqualTo("REVERSAL");
            assertThat(m.getDirection()).isEqualTo("OUT");
        });
        // The supervisor's day reports agree: each day lists its own session only.
        actAs(accountant);
        assertThat(cashSessions.dayReport(yesterday).sessions()).extracting(CashSessionDTO::getId)
            .containsExactly(yesterdaysSession);
        assertThat(cashSessions.dayReport(today).sessions()).extracting(CashSessionDTO::getId)
            .containsExactly(todaysSession);
    }
}
