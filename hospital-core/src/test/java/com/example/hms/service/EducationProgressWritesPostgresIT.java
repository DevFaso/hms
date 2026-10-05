package com.example.hms.service;

import com.example.hms.enums.EducationComprehensionStatus;
import com.example.hms.model.education.PatientEducationProgress;
import com.example.hms.repository.PatientEducationProgressRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
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

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The race {@link EducationProgressWrites} exists for, on PostgreSQL (where a
 * failed statement aborts the whole transaction unless a savepoint contains
 * it), against the schema Liquibase builds, V176's key included.
 *
 * <p>Two first requests: the first inserts and has not committed yet; the
 * second inserts the same (patient, resource), which blocks on the key until
 * the first commits, is then refused, rolls back to its savepoint only, and
 * goes on in the same transaction to read and update the first one's row.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(EducationProgressWrites.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EducationProgressWritesPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_education_writes")
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

    @Autowired
    private EducationProgressWrites writes;

    @Autowired
    private PatientEducationProgressRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private final UUID patientId = UUID.randomUUID();
    private final UUID resourceId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();

    @AfterEach
    void removeRows() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            repository.deleteAll(repository.findByPatientIdAndResourceId(patientId, resourceId)));
    }

    @Test
    void theSecondOfTwoRacingFirstRequestsUpdatesTheFirstOnesRow() throws Exception {
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() ->
            new TransactionTemplate(transactionManager).execute(status -> {
                skipForeignKeysInThisTransaction();
                boolean created = writes.insertIfAbsent(patientId, resourceId, hospitalId,
                    EducationComprehensionStatus.NOT_STARTED);
                firstInserted.countDown();
                await(releaseFirst);
                return created;
            }));
        assertThat(firstInserted.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() ->
            new TransactionTemplate(transactionManager).execute(status -> {
                skipForeignKeysInThisTransaction();
                boolean created = writes.insertIfAbsent(patientId, resourceId, hospitalId,
                    EducationComprehensionStatus.IN_PROGRESS);
                assertThat(created).as("the second insert loses the key").isFalse();
                PatientEducationProgress row = repository.findByPatientIdAndResourceId(patientId, resourceId)
                    .getFirst();
                row.setProgressPercentage(30);
                repository.saveAndFlush(row);
                return row.getProgressPercentage();
            }));

        // The second insert is waiting on the first's uncommitted key.
        assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        releaseFirst.countDown();

        assertThat(first.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(30);
        assertThat(repository.findByPatientIdAndResourceId(patientId, resourceId)).singleElement()
            .satisfies(row -> {
                assertThat(row.getComprehensionStatus()).isEqualTo(EducationComprehensionStatus.NOT_STARTED);
                assertThat(row.getProgressPercentage()).isEqualTo(30);
            });
    }

    /**
     * The fixture ids name no patient, and V169's patient key (NOT VALID, but
     * checked on new rows) would refuse them; the test user owns the
     * database, so foreign-key triggers are switched off for the current
     * transaction only. The unique key is an index, not a trigger: it still
     * applies, which is what this test is about.
     */
    private void skipForeignKeysInThisTransaction() {
        entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
