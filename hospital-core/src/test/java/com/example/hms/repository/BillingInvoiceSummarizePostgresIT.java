package com.example.hms.repository;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D209: the billing KPIs are one aggregate over every invoice in scope, not
 * a sum of the page on screen. Mixed statuses are written for two hospitals
 * (more rows than one page of the list holds) and the JPQL is run on a real
 * PostgreSQL built by Liquibase, for one hospital and for the global view.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BillingInvoiceSummarizePostgresIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_billing_summary")
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

    @Autowired private BillingInvoiceRepository repository;
    @Autowired private PaymentTransactionRepository payments;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private final List<UUID> saved = new ArrayList<>();
    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            // Hospital A: 30 rows, more than a 20-row page.
            for (int i = 0; i < 12; i++) {
                insert(hospitalA, InvoiceStatus.PAID, "100", "100", TODAY.minusDays(5));
            }
            for (int i = 0; i < 8; i++) {
                insert(hospitalA, InvoiceStatus.SENT, "100", "0", TODAY.minusDays(1)); // overdue
            }
            for (int i = 0; i < 5; i++) {
                insert(hospitalA, InvoiceStatus.PARTIALLY_PAID, "100", "40", TODAY.plusDays(3));
            }
            for (int i = 0; i < 3; i++) {
                insert(hospitalA, InvoiceStatus.CANCELLED, "100", "100", TODAY.minusDays(9));
            }
            insert(hospitalA, InvoiceStatus.DRAFT, "100", "0", TODAY.minusDays(9));
            insert(hospitalA, InvoiceStatus.SENT, "100", "0", TODAY); // due today: not overdue
            // Hospital B: 2 rows.
            insert(hospitalB, InvoiceStatus.PAID, "50", "50", TODAY);
            insert(hospitalB, InvoiceStatus.SENT, "80", "0", TODAY.minusDays(2));
        });
    }

    @AfterEach
    void removeRows() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> repository.deleteAllById(saved));
    }

    @Test
    void oneHospitalCountsEveryRowNotOnePage() {
        Object[] row = repository.summarize(hospitalA, TODAY).get(0);

        assertThat(((Number) row[0]).longValue()).isEqualTo(30);
        assertThat(((Number) row[1]).longValue()).isEqualTo(12);
        assertThat(((Number) row[2]).longValue()).isEqualTo(14);
        assertThat(((Number) row[3]).longValue()).isEqualTo(8);
        // revenue: 12×100 paid + 5×40 part-paid; cancelled money is not revenue
        assertThat(new BigDecimal(row[4].toString())).isEqualByComparingTo("1400");
        // balance: 9×100 sent + 5×60 part-paid
        assertThat(new BigDecimal(row[5].toString())).isEqualByComparingTo("1200");
    }

    @Test
    void globalViewReadsEveryHospital() {
        Object[] row = repository.summarize(null, TODAY).get(0);

        assertThat(((Number) row[0]).longValue()).isEqualTo(32);
        assertThat(((Number) row[1]).longValue()).isEqualTo(13);
        assertThat(((Number) row[3]).longValue()).isEqualTo(9);
        assertThat(new BigDecimal(row[4].toString())).isEqualByComparingTo("1450");
        assertThat(new BigDecimal(row[5].toString())).isEqualByComparingTo("1280");
    }

    @Test
    void anEmptyScopeIsOneRowOfNulls() {
        List<Object[]> rows = repository.summarize(UUID.randomUUID(), TODAY);

        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0)[0]).longValue()).isZero();
        assertThat(rows.get(0)[5]).isNull();
    }

    @Test
    void searchFindsTheInvoiceByThePatientsMrnAtThatHospitalOnly_D276() {
        UUID patientId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            BillingInvoice invoice = new BillingInvoice();
            invoice.setHospital(entityManager.getReference(Hospital.class, hospitalA));
            invoice.setPatient(entityManager.getReference(Patient.class, patientId));
            invoice.setInvoiceNumber("IT-" + UUID.randomUUID().toString().substring(0, 12));
            invoice.setInvoiceDate(TODAY);
            invoice.setDueDate(TODAY);
            invoice.setTotalAmount(new BigDecimal("100"));
            invoice.setAmountPaid(BigDecimal.ZERO);
            invoice.setStatus(InvoiceStatus.SENT);
            saved.add(repository.saveAndFlush(invoice).getId());
            // The same patient's MRN at another hospital must not match here.
            registration(patientId, hospitalA, "MRN-QA-R5-A");
            registration(patientId, hospitalB, "MRN-QA-R5-B");
        });
        try {
            // A page past the end: no rows are loaded (this test writes no hospital
            // or patient rows for the fetch joins) and the total comes from the count query.
            var page = org.springframework.data.domain.PageRequest.of(1000, 1);
            assertThat(repository.findAllWithFilters(null, null, null, null, null, "%mrn-qa-r5-a%", page)
                .getTotalElements()).isEqualTo(1);
            assertThat(repository.findAllWithFilters(null, null, null, null, null, "%mrn-qa-r5-b%", page)
                .getTotalElements()).isZero();
            // (No hospital rows exist here, so the scope is left open; the MRN is still
            // matched only at the invoice's own hospital.)
            // Invoices with no encounter still match on their number (the encounter join is outer).
            assertThat(repository.findAllWithFilters(null, null, null, null, null, "%it-%", page)
                .getTotalElements()).isEqualTo(33);
        } finally {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> entityManager
                .createNativeQuery("DELETE FROM clinical.patient_hospital_registrations WHERE patient_id = :p")
                .setParameter("p", patientId).executeUpdate());
        }
    }

    @Test
    void aLiveProviderReferenceIsFoundAtItsHospitalOnly_D345() {
        List<com.example.hms.enums.PaymentStatus> live = List.of(
            com.example.hms.enums.PaymentStatus.PENDING, com.example.hms.enums.PaymentStatus.CONFIRMED);
        List<UUID> rows = new ArrayList<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            BillingInvoice invoice = repository.getReferenceById(saved.get(0)); // hospital A
            rows.add(payment(invoice, com.example.hms.enums.PaymentMethod.CARD, " OM-7781 ",
                com.example.hms.enums.PaymentStatus.PENDING));
            rows.add(payment(invoice, com.example.hms.enums.PaymentMethod.CASH, "CASH-1",
                com.example.hms.enums.PaymentStatus.CONFIRMED));
            rows.add(payment(invoice, com.example.hms.enums.PaymentMethod.CARD, "REV-1",
                com.example.hms.enums.PaymentStatus.REVERSED));
        });
        try {
            assertThat(payments.existsLiveReference(hospitalA, "om-7781", live)).isTrue();
            assertThat(payments.existsLiveReference(hospitalB, "om-7781", live)).isFalse();
            assertThat(payments.existsLiveReference(hospitalA, "cash-1", live)).isFalse();
            assertThat(payments.existsLiveReference(hospitalA, "rev-1", live)).isFalse();
        } finally {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> payments.deleteAllById(rows));
        }
    }

    private UUID payment(BillingInvoice invoice, com.example.hms.enums.PaymentMethod method, String reference,
                         com.example.hms.enums.PaymentStatus status) {
        com.example.hms.model.PaymentTransaction row = new com.example.hms.model.PaymentTransaction();
        row.setInvoice(invoice);
        row.setAmount(new BigDecimal("10"));
        row.setPaymentDate(TODAY);
        row.setPaymentMethod(method);
        row.setReferenceNumber(reference);
        row.setStatus(status);
        return payments.saveAndFlush(row).getId();
    }

    private void registration(UUID patientId, UUID hospitalId, String mrn) {
        entityManager.createNativeQuery("""
            INSERT INTO clinical.patient_hospital_registrations
                (id, mrn, patient_id, hospital_id, registration_date, is_active, stay_status, created_at, updated_at)
            VALUES (:id, :mrn, :p, :h, CURRENT_DATE, true, 'ADMITTED', now(), now())
            """)
            .setParameter("id", UUID.randomUUID()).setParameter("mrn", mrn)
            .setParameter("p", patientId).setParameter("h", hospitalId).executeUpdate();
    }

    private void insert(UUID hospitalId, InvoiceStatus status, String total, String paid, LocalDate due) {
        BillingInvoice invoice = new BillingInvoice();
        invoice.setHospital(entityManager.getReference(Hospital.class, hospitalId));
        invoice.setPatient(entityManager.getReference(Patient.class, UUID.randomUUID()));
        invoice.setInvoiceNumber("IT-" + UUID.randomUUID().toString().substring(0, 12));
        invoice.setInvoiceDate(due.minusDays(10));
        invoice.setDueDate(due);
        invoice.setTotalAmount(new BigDecimal(total));
        invoice.setAmountPaid(new BigDecimal(paid));
        invoice.setStatus(status);
        saved.add(repository.saveAndFlush(invoice).getId());
    }
}
