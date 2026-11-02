package com.example.hms.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1324 (round 29): the waitlist "Tous" filter now reaches
 * {@code findByHospitalFiltered} with a null status (it was always defaulted to
 * WAITING, so the null branch never ran). Every combination its caller passes
 * (department set or null, status set or null) runs on a real PostgreSQL built
 * by Liquibase, and never lists another hospital's entry.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("D1324: the waitlist filter, every status and department combination, on PostgreSQL")
class WaitlistFilterPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_waitlist_filter")
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

    @Autowired private AppointmentWaitlistRepository repository;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();
    private final UUID departmentA = UUID.randomUUID();
    private final UUID otherDepartment = UUID.randomUUID();
    private final List<UUID> rows = new ArrayList<>();

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            rows.add(entry(hospitalA, departmentA, "WAITING"));
            rows.add(entry(hospitalA, departmentA, "OFFERED"));
            rows.add(entry(hospitalA, otherDepartment, "CLOSED"));
            rows.add(entry(hospitalB, departmentA, "WAITING"));
        });
    }

    @AfterEach
    void clear() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.createNativeQuery("DELETE FROM scheduling.appointment_waitlist WHERE id IN (:ids)")
                .setParameter("ids", rows).executeUpdate());
        rows.clear();
    }

    @Test
    @DisplayName("every department and status combination, nulls included, answers hospital A's entries only")
    void everyCombinationRuns() {
        List<String> wrong = new ArrayList<>();
        for (UUID department : Arrays.asList(null, departmentA)) {
            for (String status : Arrays.asList(null, "WAITING", "OFFERED", "CLOSED")) {
                int expected = expected(department, status);
                int found = repository.findByHospitalFiltered(hospitalA, department, status).size();
                if (found != expected) {
                    wrong.add("department=" + department + " status=" + status + " -> " + found + " (expected "
                        + expected + ")");
                }
            }
        }
        assertThat(wrong).isEmpty();
    }

    private int expected(UUID department, String status) {
        int count = 0;
        String[][] seeded = {{"A", "WAITING"}, {"A", "OFFERED"}, {"O", "CLOSED"}};
        for (String[] row : seeded) {
            boolean departmentMatches = department == null || "A".equals(row[0]);
            boolean statusMatches = status == null || status.equals(row[1]);
            if (departmentMatches && statusMatches) {
                count++;
            }
        }
        return count;
    }

    private UUID entry(UUID hospital, UUID department, String status) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery("""
            INSERT INTO scheduling.appointment_waitlist (id, hospital_id, department_id, patient_id, priority, status,
                created_at, updated_at)
            VALUES (:id, :h, :d, :p, 'ROUTINE', :s, now(), now())
            """).setParameter("id", id).setParameter("h", hospital).setParameter("d", department)
            .setParameter("p", UUID.randomUUID()).setParameter("s", status).executeUpdate();
        return id;
    }
}
