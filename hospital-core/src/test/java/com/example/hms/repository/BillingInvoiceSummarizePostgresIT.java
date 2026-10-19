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
