package com.example.hms.service.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.pharmacy.InventoryItem;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.StockTransaction;
import com.example.hms.repository.pharmacy.StockTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

/** Runs the report's actual grouped query, including its legacy reversal classification, on PostgreSQL. */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(com.example.hms.security.EncryptionKeyHolder.class)
class PharmacyConsumptionPostgresIT {
    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_consumption").withUsername("hms_test_user").withPassword("hms_test_pass");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @PersistenceContext EntityManager entityManager;
    @Autowired StockTransactionRepository ledger;

    @Test
    void transferReturnsAreExcludedWhileLinkedAndRecognizableLegacyDispenseReversalsRemain() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        UUID userId = UUID.randomUUID();
        entityManager.createNativeQuery("""
            INSERT INTO security.users (id, username, first_name, last_name, password_hash, email,
                phone_number, is_active, is_deleted, force_password_change, created_at, updated_at)
            VALUES (:id, :username, 'Synthetic', 'Counter', 'x', :email, :phone,
                true, false, false, now(), now())
            """).setParameter("id", userId).setParameter("username", "consumption-" + tag)
            .setParameter("email", tag + "@example.test").setParameter("phone", "+226" + tag).executeUpdate();
        Hospital hospital = new Hospital();
        hospital.setCode("CONSUMPTION-" + tag);
        hospital.setName("Synthetic consumption hospital");
        entityManager.persist(hospital);
        Pharmacy pharmacy = Pharmacy.builder().hospital(hospital).name("Central").build();
        entityManager.persist(pharmacy);
        MedicationCatalogItem medicine = MedicationCatalogItem.builder().nameFr("Synthetic medicine")
            .genericName("Synthetic medicine").code(tag).build();
        entityManager.persist(medicine);
        InventoryItem item = InventoryItem.builder().pharmacy(pharmacy).medicationCatalogItem(medicine).unit("tablet").build();
        entityManager.persist(item);
        User actor = entityManager.find(User.class, userId);
        movement(item, actor, StockTransactionType.DISPENSE, "10", null, "Synthetic issue");
        StockTransaction transfer = movement(item, actor, StockTransactionType.TRANSFER, "8", UUID.randomUUID(), "Ward transfer");
        movement(item, actor, StockTransactionType.RETURN, "3", UUID.randomUUID(), "Patient return");
        movement(item, actor, StockTransactionType.RETURN, "6", transfer.getId(), "Referenced transfer return");
        movement(item, actor, StockTransactionType.RETURN, "2", null,
            "Dispense cancelled - stock returned for prescription synthetic");
        movement(item, actor, StockTransactionType.RETURN, "8", null, "Unreferenced transfer return");
        entityManager.flush();
        entityManager.clear();
        var rows = ledger.consumptionByRecordedUnit(pharmacy.getId(), LocalDate.now().minusDays(1).atStartOfDay(),
            LocalDate.now().plusDays(1).atStartOfDay(), Set.of(StockTransactionType.DISPENSE, StockTransactionType.SALE),
            StockTransactionType.RETURN, StockTransactionType.TRANSFER);
        assertThat(rows).hasSize(1);
        assertThat(new BigDecimal(rows.getFirst()[3].toString())).isEqualByComparingTo("10");
        assertThat(new BigDecimal(rows.getFirst()[4].toString())).isEqualByComparingTo("5");
    }

    private StockTransaction movement(InventoryItem item, User actor, StockTransactionType type, String quantity,
                                      UUID reference, String reason) {
        return ledger.saveAndFlush(StockTransaction.builder().inventoryItem(item).performedByUser(actor)
            .transactionType(type).quantity(new BigDecimal(quantity)).referenceId(reference).reason(reason).build());
    }
}
