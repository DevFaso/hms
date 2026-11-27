package com.example.hms.repository;

import com.example.hms.model.Notification;
import com.example.hms.security.PhiTextEncryptionBackfill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scorecard K4 (V387), on real PostgreSQL built by Liquibase: the in-app notification is ciphertext at rest, its
 * body in a TEXT column and its arguments sealed inside the jsonb document, and reads back whole; a row written
 * before is read as it is and encrypted by the startup backfill, once.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@org.springframework.context.annotation.Import(com.example.hms.security.EncryptionKeyHolder.class)
@DisplayName("K4: the in-app notification is encrypted at rest (PostgreSQL)")
class NotificationAtRestPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_notifications_at_rest")
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

    private static final String BODY = "Résultat critique : Potassium 6,8 mmol/L pour Traore Awa";

    @Autowired private NotificationRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private Notification saved(String recipient, String body, List<Object> args) {
        return repository.save(Notification.builder().message(body).messageKey("k4b.test.key").messageArgs(args)
            .recipientUsername(recipient).type("CRITICAL_LAB_RESULT").createdAt(LocalDateTime.now()).read(false)
            .build());
    }

    private String storedBody(UUID id) {
        return jdbc.queryForObject("SELECT message FROM security.notifications WHERE id = ?", String.class, id);
    }

    private String storedArgs(UUID id) {
        return jdbc.queryForObject("SELECT CAST(message_args AS TEXT) FROM security.notifications WHERE id = ?",
            String.class, id);
    }

    @Test
    @DisplayName("V387: the body column is TEXT and the arguments column is still jsonb")
    void theColumns() {
        Map<String, String> types = new java.util.HashMap<>();
        jdbc.query("SELECT column_name, data_type FROM information_schema.columns"
                + " WHERE table_schema = 'security' AND table_name = 'notifications'",
            row -> { types.put(row.getString(1), row.getString(2)); });
        assertThat(types).containsEntry("message", "text").containsEntry("message_args", "jsonb");
    }

    @Test
    @DisplayName("a notification written now is ciphertext at rest, body and arguments, and reads back whole")
    void writtenNowIsCiphertext() {
        String recipient = "k4b-" + UUID.randomUUID();
        String longBody = (BODY + ". ").repeat(6);
        List<Object> args = List.of(7, "Traore Awa", Map.of("key", "lab.test.potassium"));
        UUID id = saved(recipient, longBody, args).getId();
        UUID bare = saved(recipient, "Sans argument", null).getId();

        assertThat(longBody.length()).as("longer than the column once held").isGreaterThan(255);
        assertThat(storedBody(id)).startsWith("gcm1:").doesNotContain("Traore", "Potassium");
        assertThat(storedArgs(id)).startsWith("{\"sealed\": \"gcm1:").doesNotContain("Traore", "potassium");
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(message_args) FROM security.notifications WHERE id = ?",
            String.class, id)).isEqualTo("object");
        assertThat(storedArgs(bare)).isNull();

        Notification read = repository.findById(id).orElseThrow();
        assertThat(read.getMessage()).isEqualTo(longBody);
        assertThat(read.getMessageArgs()).isEqualTo(args);
        assertThat(repository.findById(bare).orElseThrow().getMessageArgs()).isNull();
        // The list the bell reads, and the unread count, do not depend on the body.
        assertThat(repository.findByRecipientUsername(recipient, PageRequest.of(0, 10)).getContent())
            .extracting(Notification::getMessage).containsExactlyInAnyOrder(longBody, "Sans argument");
        assertThat(repository.countByRecipientUsernameAndReadFalse(recipient)).isEqualTo(2);
    }

    @Test
    @DisplayName("a notification written before is read as it is, then encrypted by the backfill, once")
    void writtenBeforeIsBackfilled() {
        UUID id = saved("k4b-" + UUID.randomUUID(), "to be replaced", List.of("x")).getId();
        UUID bare = saved("k4b-" + UUID.randomUUID(), "to be replaced", null).getId();
        jdbc.update("UPDATE security.notifications SET message = ?, message_args = CAST(? AS jsonb) WHERE id = ?",
            BODY, "[7, \"Traore Awa\", {\"key\": \"a.b\"}]", id);
        jdbc.update("UPDATE security.notifications SET message = ?, message_args = NULL WHERE id = ?", BODY, bare);
        List<Object> legacyArgs = List.of(7, "Traore Awa", Map.of("key", "a.b"));

        Notification before = repository.findById(id).orElseThrow();
        assertThat(before.getMessage()).isEqualTo(BODY);
        assertThat(before.getMessageArgs()).isEqualTo(legacyArgs);

        PhiTextEncryptionBackfill backfill = new PhiTextEncryptionBackfill(jdbc, transactionManager);
        backfill.backfill();

        String body = storedBody(id);
        String args = storedArgs(id);
        assertThat(body).startsWith("gcm1:").doesNotContain("Traore");
        assertThat(args).startsWith("{\"sealed\": \"gcm1:").doesNotContain("Traore");
        assertThat(storedBody(bare)).startsWith("gcm1:");
        assertThat(storedArgs(bare)).isNull();
        Notification after = repository.findById(id).orElseThrow();
        assertThat(after.getMessage()).isEqualTo(BODY);
        assertThat(after.getMessageArgs()).isEqualTo(legacyArgs);

        backfill.backfill();

        assertThat(storedBody(id)).as("idempotent").isEqualTo(body);
        assertThat(storedArgs(id)).as("idempotent").isEqualTo(args);
    }
}
