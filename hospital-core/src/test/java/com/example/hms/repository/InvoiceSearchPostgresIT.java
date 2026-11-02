package com.example.hms.repository;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AppointmentFilterDTO;
import com.example.hms.service.BillingInvoiceServiceImpl;
import com.example.hms.specification.AppointmentSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * QA D1310 (round 28) on a real PostgreSQL built by Liquibase: the invoice
 * list's search finds the patient by e-mail (whole or a piece), phone (any
 * written form), birth date (any accepted format), name, MRN and invoice
 * number, through the service's routing ({@code typedBirthDate},
 * {@code typedPhonePattern}), for EVERY combination of the other filters the
 * service passes (patient, hospital scope, statuses, dates, each set or
 * null), and never an invoice of another hospital. The appointment list's
 * search (the same class) runs its new predicates too.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("D1310: the invoice search finds the patient by every identifier, on PostgreSQL")
class InvoiceSearchPostgresIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);
    private static final String EMAIL = "inoussa.sawadogo.r28@example.com";

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_invoice_search")
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
    @Autowired private AppointmentRepository appointments;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final List<UUID> invoices = new ArrayList<>();
    private String invoiceNumber;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO clinical.patients (id, first_name, last_name, date_of_birth, phone_number_primary,
                    phone_number_secondary, email, is_active, chart_restricted, created_at, updated_at)
                VALUES (:id, 'Inoussa', 'Sawadogo', DATE '1990-06-15', '+226 70 28 43 17', '65-11-22-33', :email,
                    true, false, now(), now())
                """).setParameter("id", patientId).setParameter("email", EMAIL).executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO clinical.patient_hospital_registrations
                    (id, mrn, patient_id, hospital_id, registration_date, is_active, stay_status, created_at, updated_at)
                VALUES (:id, 'CHW8266', :p, :h, CURRENT_DATE, true, 'ADMITTED', now(), now())
                """).setParameter("id", UUID.randomUUID()).setParameter("p", patientId)
                .setParameter("h", hospitalA).executeUpdate();
            invoiceNumber = "INV-R28-" + UUID.randomUUID().toString().substring(0, 8);
            invoices.add(invoice(hospitalA, invoiceNumber));
            // The same patient billed at another hospital: never in hospital A's list.
            invoices.add(invoice(hospitalB, "INV-R28B-" + UUID.randomUUID().toString().substring(0, 8)));
        });
    }

    @AfterEach
    void clear() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.deleteAllById(invoices);
            entityManager.createNativeQuery("DELETE FROM clinical.patient_hospital_registrations WHERE patient_id = :p")
                .setParameter("p", patientId).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM clinical.patients WHERE id = :p")
                .setParameter("p", patientId).executeUpdate();
        });
        invoices.clear();
    }

    @Test
    @DisplayName("every identifier typed finds hospital A's invoice, under every combination of the other filters")
    void everyIdentifierFindsTheInvoice() {
        List<String> typed = List.of(EMAIL, "inoussa.sawadogo", "70284317", "+226 70 28 43 17", "65112233",
            "15/06/1990", "1990-06-15", "15-06-1990", "Sawadogo", "inoussa sawadogo", "CHW8266", invoiceNumber);
        List<String> missed = new ArrayList<>();
        int runs = 0;
        for (String term : typed) {
            for (UUID patient : Arrays.asList(null, patientId)) {
                for (List<InvoiceStatus> statuses : Arrays.asList(null, List.of(InvoiceStatus.SENT))) {
                    for (boolean dated : new boolean[]{false, true}) {
                        runs++;
                        long inA = count(patient, hospitalA, statuses, dated, term);
                        long everywhere = count(patient, null, statuses, dated, term);
                        if (inA != 1) {
                            missed.add(term + " patient=" + patient + " statuses=" + statuses + " dated=" + dated
                                + " -> " + inA);
                        }
                        // The global view (super-admin) reads hospital B too; only a typed MRN is per hospital.
                        long expectedEverywhere = term.equals("CHW8266") || term.equals(invoiceNumber) ? 1 : 2;
                        if (everywhere != expectedEverywhere) {
                            missed.add(term + " global -> " + everywhere);
                        }
                    }
                }
            }
        }
        assertThat(runs).isEqualTo(typed.size() * 8);
        assertThat(missed).isEmpty();
    }

    @Test
    @DisplayName("nothing typed lists the hospital's invoices; a wrong identifier finds none")
    void wrongIdentifiersFindNothing() {
        assertThat(count(null, hospitalA, null, false, null)).isEqualTo(1);
        for (String wrong : List.of("someone.else@example.com", "70999999", "16/06/1990", "Ouedraogo", "CHW0000")) {
            assertThat(count(null, hospitalA, null, false, wrong)).as(wrong).isZero();
        }
        // The invoices of hospital B are never listed in hospital A's scope.
        assertThat(count(null, hospitalB, null, false, invoiceNumber)).isZero();
    }

    @Test
    @DisplayName("the appointment list's search runs its e-mail, birth-date and phone predicates")
    void theAppointmentSearchRunsOnPostgres() {
        for (String term : List.of(EMAIL, "15/06/1990", "70284317", "+226 70 28 43 17", "Sawadogo")) {
            AppointmentFilterDTO filter = AppointmentFilterDTO.builder().search(term).build();
            assertThatCode(() -> appointments.findAll(AppointmentSpecification.withFilter(filter), PageRequest.of(0, 20)))
                .as(term).doesNotThrowAnyException();
        }
    }

    private long count(UUID patient, UUID hospital, List<InvoiceStatus> statuses, boolean dated, String term) {
        return repository.findAllWithFilters(patient, hospital, statuses,
                dated ? TODAY.minusDays(30) : null, dated ? TODAY.plusDays(1) : null,
                likePattern(term), BillingInvoiceServiceImpl.typedBirthDate(term),
                BillingInvoiceServiceImpl.typedPhonePattern(term), PageRequest.of(1000, 1))
            .getTotalElements();
    }

    /** The service's own term pattern (package-private there): lower case, wildcards removed. */
    private static String likePattern(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String t = term.trim().toLowerCase(java.util.Locale.ROOT).replace("%", "").replace("_", "");
        return t.isEmpty() ? null : "%" + t + "%";
    }

    private UUID invoice(UUID hospitalId, String number) {
        BillingInvoice invoice = new BillingInvoice();
        invoice.setHospital(entityManager.getReference(Hospital.class, hospitalId));
        invoice.setPatient(entityManager.getReference(Patient.class, patientId));
        invoice.setInvoiceNumber(number);
        invoice.setInvoiceDate(TODAY);
        invoice.setDueDate(TODAY);
        invoice.setTotalAmount(new BigDecimal("100"));
        invoice.setAmountPaid(BigDecimal.ZERO);
        invoice.setStatus(InvoiceStatus.SENT);
        return repository.saveAndFlush(invoice).getId();
    }
}
