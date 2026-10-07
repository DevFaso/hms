package com.example.hms.repository.pharmacy;

import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.Pharmacy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code clinical.dispenses.verification_overrides} is JSONB (V138). Mapped as
 * a plain String it bound as VARCHAR, and PostgreSQL refused every INSERT of a
 * dispense, null or not; H2 accepted it, so only a real PostgreSQL built by
 * Liquibase shows the binding. Both a dispense without overrides and one with
 * a JSON array are written through the repository and read back in a new
 * transaction.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DispenseJsonbPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_dispense_jsonb")
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
    private DispenseRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private final List<UUID> saved = new ArrayList<>();

    @AfterEach
    void removeRows() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            repository.deleteAllById(saved));
    }

    @Test
    void aDispenseWithoutOverridesIsInsertedAndReadBack() {
        UUID id = insert(null);

        Dispense row = readBack(id);
        assertThat(row.getVerificationOverrides()).isNull();
        assertThat(row.getMedicationName()).isEqualTo("Amoxicillin 500 mg");
    }

    @Test
    void aDispenseWithOverridesStoresThemAsAJsonbArray() {
        UUID id = insert("[\"PATIENT\", \"DRUG\"]");

        Dispense row = readBack(id);
        assertThat(row.getVerificationOverrides()).isNotNull();
        assertThat(row.getVerificationOverrides().replace(" ", "")).isEqualTo("[\"PATIENT\",\"DRUG\"]");

        Object storedType = new TransactionTemplate(transactionManager).execute(status ->
            entityManager.createNativeQuery(
                    "SELECT jsonb_typeof(verification_overrides) FROM clinical.dispenses WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult());
        assertThat(storedType).isEqualTo("array");
    }

    private UUID insert(String overrides) {
        UUID id = new TransactionTemplate(transactionManager).execute(status -> {
            skipForeignKeysInThisTransaction();
            Dispense dispense = Dispense.builder()
                .prescription(entityManager.getReference(Prescription.class, UUID.randomUUID()))
                .patient(entityManager.getReference(Patient.class, UUID.randomUUID()))
                .pharmacy(entityManager.getReference(Pharmacy.class, UUID.randomUUID()))
                .dispensedByUser(entityManager.getReference(User.class, UUID.randomUUID()))
                .medicationName("Amoxicillin 500 mg")
                .quantityRequested(new BigDecimal("10"))
                .quantityDispensed(new BigDecimal("10"))
                .verificationOverrides(overrides)
                .build();
            return repository.saveAndFlush(dispense).getId();
        });
        saved.add(id);
        return id;
    }

    private Dispense readBack(UUID id) {
        return new TransactionTemplate(transactionManager).execute(status ->
            repository.findById(id).orElseThrow());
    }

    /**
     * The fixture ids name no prescription, patient, pharmacy or user; the
     * test user owns the database, so foreign-key triggers are switched off
     * for the current transaction only. The column types still apply, which
     * is what this test is about.
     */
    private void skipForeignKeysInThisTransaction() {
        entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
    }
}
