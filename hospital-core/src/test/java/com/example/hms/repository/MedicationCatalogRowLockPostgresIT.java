package com.example.hms.repository;

import com.example.hms.model.medication.MedicationCatalogItem;
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

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review F1 of #1360 (go-live D2, V385): an approval applied a version to the catalogue row read without a lock,
 * so an administrator's edit or withdrawal committed meanwhile was overwritten, or undid the approved values with
 * its own stale copy. Every write path (the approval's {@code entryOf}, the edit's and the deactivation's
 * {@code requireWritable}) now reads the row through {@link MedicationCatalogItemRepository#findLockedById}. On a
 * real PostgreSQL built by Liquibase: a second locked read WAITS for the first transaction, and then reads what it
 * committed (a withdrawal is not undone by an approval that started before it committed).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MedicationCatalogRowLockPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_catalog_lock")
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

    @Autowired private MedicationCatalogItemRepository catalogue;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void aSecondWriterWaitsForTheFirst_andReadsTheWithdrawalItCommitted() throws Exception {
        UUID id = new TransactionTemplate(transactionManager).execute(status -> catalogue.save(
            MedicationCatalogItem.builder().nameFr("Paracétamol " + UUID.randomUUID()).genericName("Paracetamol")
                .form("TABLET").strength("500").strengthUnit("mg").route("ORAL").build()).getId());

        CountDownLatch firstHoldsTheLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CompletableFuture<Void> withdrawal = CompletableFuture.runAsync(() ->
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                MedicationCatalogItem item = catalogue.findLockedById(id).orElseThrow();
                firstHoldsTheLock.countDown();
                await(releaseFirst);
                item.deactivate();
                catalogue.save(item);
            }));
        assertThat(firstHoldsTheLock.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Boolean> approvalSeesActive = CompletableFuture.supplyAsync(() ->
            new TransactionTemplate(transactionManager).execute(status ->
                catalogue.findLockedById(id).orElseThrow().isActive()));

        // The approval's locked read waits while the withdrawal holds the row.
        Thread.sleep(1500);
        assertThat(approvalSeesActive).isNotDone();

        releaseFirst.countDown();
        withdrawal.get(30, TimeUnit.SECONDS);
        // Once the withdrawal commits, the approval reads the row as withdrawn, so it cannot put it back.
        assertThat(approvalSeesActive.get(30, TimeUnit.SECONDS)).isFalse();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
