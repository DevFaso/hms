package com.example.hms.repository;

import com.example.hms.enums.InBasketItemStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A hospital administrator's in-basket on real PostgreSQL, for every combination of parameters its callers use: an
 * item addressed to them of another kind, and a dispute review addressed to somebody else, are not read.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InBasketDisputeReviewPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_in_basket_review")
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

    private static final String TYPE = "DEATH_DISPUTE";

    @Autowired private InBasketItemRepository items;
    @Autowired private JdbcTemplate jdbc;

    private void item(UUID user, UUID hospital, String referenceType) {
        StringBuilder columns = new StringBuilder("id, recipient_user_id, hospital_id, reference_type, status");
        StringBuilder marks = new StringBuilder("?, ?, ?, ?, 'UNREAD'");
        java.util.List<Object> args = new java.util.ArrayList<>(java.util.List.of(UUID.randomUUID(), user, hospital, referenceType));
        for (Map<String, Object> column : jdbc.queryForList("""
                SELECT column_name, data_type FROM information_schema.columns
                WHERE table_schema = 'clinical' AND table_name = 'in_basket_items' AND is_nullable = 'NO' AND column_default IS NULL
                """)) {
            String col = (String) column.get("column_name");
            if (!java.util.Set.of("id", "recipient_user_id", "hospital_id", "reference_type", "status").contains(col)) {
                columns.append(", ").append(col);
                marks.append(", ?");
                args.add(switch ((String) column.get("data_type")) {
                    case "uuid" -> UUID.randomUUID();
                    case "boolean" -> Boolean.FALSE;
                    case "timestamp without time zone" -> java.sql.Timestamp.valueOf(java.time.LocalDateTime.now());
                    default -> "x";
                });
            }
        }
        jdbc.execute("ALTER TABLE clinical.in_basket_items DISABLE TRIGGER ALL");
        try {
            jdbc.update("INSERT INTO clinical.in_basket_items (" + columns + ") VALUES (" + marks + ")", args.toArray());
        } finally {
            jdbc.execute("ALTER TABLE clinical.in_basket_items ENABLE TRIGGER ALL");
        }
    }

    @Test
    @DisplayName("their dispute reviews, here; not another kind of item, nor another's review, nor another hospital's")
    void onlyTheirDisputeReviews() {
        UUID admin = UUID.randomUUID();
        UUID hospital = UUID.randomUUID();
        UUID elsewhere = UUID.randomUUID();
        item(admin, hospital, TYPE);
        item(admin, elsewhere, TYPE);
        item(admin, hospital, "LAB_RESULT");
        item(UUID.randomUUID(), hospital, TYPE);

        assertThat(items.findByRecipientAndReferenceType(admin, hospital, TYPE, null, Pageable.unpaged()).getTotalElements()).isEqualTo(1);
        assertThat(items.findByRecipientAndReferenceType(admin, hospital, TYPE, InBasketItemStatus.UNREAD, Pageable.unpaged()).getTotalElements()).isEqualTo(1);
        assertThat(items.findByRecipientAndReferenceType(admin, hospital, TYPE, InBasketItemStatus.READ, Pageable.unpaged()).getTotalElements()).isZero();
        assertThat(items.findByRecipientAndReferenceType(admin, null, TYPE, null, Pageable.unpaged()).getTotalElements())
            .as("a super admin's global view").isEqualTo(2);
        assertThat(items.countByRecipientAndReferenceType(admin, hospital, TYPE, InBasketItemStatus.UNREAD)).isEqualTo(1);
        assertThat(items.countByRecipientAndReferenceType(admin, null, TYPE, InBasketItemStatus.UNREAD)).isEqualTo(2);
    }
}
