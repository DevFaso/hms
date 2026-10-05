package com.example.hms.service.integration.message;

import com.example.hms.HmsApplication;
import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.model.integration.IntegrationMessageEvent;
import com.example.hms.repository.integration.IntegrationMessageEventRepository;
import com.example.hms.service.SuperAdminIntegrationMessageService;
import com.example.hms.service.scheduled.IntegrationMessageRetentionScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The content-retention sweep (V177) on real PostgreSQL, through the real
 * queries: which rows lose their body, which keep it, that the rows
 * themselves survive, that a rerun changes nothing, and that a purged dead
 * letter refuses replay with a 409 rather than replaying nothing.
 *
 * <p>Postgres rather than H2 because the purge is a bulk UPDATE whose
 * eligibility check is a correlated subquery on the table being updated, and
 * because the column is encrypted - the test reads the raw column to prove
 * the ciphertext is gone, not just the decrypted view of it.
 */
@Testcontainers
@SpringBootTest(classes = HmsApplication.class)
@ActiveProfiles("test")
class IntegrationMessageRetentionIT {

    private static final int DAYS = 180;

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_retention")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    @DynamicPropertySource
    static void realDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");   // schema-h2.sql is H2-only
        // The test drives its own scheduler instance; the bean stays inert.
        registry.add("hms.integration.retention.enabled", () -> "false");
    }

    @Autowired
    private IntegrationMessageEventRepository repository;
    @Autowired
    private IntegrationMessageRetentionService retentionService;
    @Autowired
    private SuperAdminIntegrationMessageService messageService;
    @Autowired
    private JdbcTemplate jdbc;

    private final LocalDateTime now = LocalDateTime.now();
    private final LocalDateTime old = now.minusDays(DAYS + 30L);

    @BeforeEach
    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    private IntegrationMessageRetentionScheduler sweep(int batchSize) {
        return new IntegrationMessageRetentionScheduler(
            retentionService, Clock.systemDefaultZone(), true, DAYS, batchSize, 200);
    }

    private IntegrationMessageEvent row(IntegrationMessageStatus status, String correlationId,
                                        String payload, LocalDateTime at) {
        return repository.saveAndFlush(IntegrationMessageEvent.builder()
            .integrationId("MLLP:LAB|SITE")
            .direction(IntegrationMessageDirection.INBOUND)
            .messageType("ORU^R01")
            .correlationId(correlationId)
            .payload(payload)
            .status(status)
            .errorMessage(status == IntegrationMessageStatus.FAILED ? "unparseable ORU^R01" : null)
            .attemptCount(1)
            .lastAttemptedAt(at)
            .receivedAt(at)
            .build());
    }

    private Map<String, Object> raw(UUID id) {
        return jdbc.queryForMap(
            "SELECT payload, payload_purged_at, status, message_type "
                + "FROM clinical.integration_message_event WHERE id = ?", id);
    }

    @Test
    void purgesOldContentKeepsTheRowAndLeavesEverythingElseAlone() {
        IntegrationMessageEvent oldReceived = row(IntegrationMessageStatus.RECEIVED, "c-1", "MSH|old", old);
        IntegrationMessageEvent recentReceived =
            row(IntegrationMessageStatus.RECEIVED, "c-2", "MSH|recent", now.minusDays(DAYS - 10L));
        IntegrationMessageEvent oldNoBody = row(IntegrationMessageStatus.SENT, "c-3", null, old);

        Integer purged = sweep(500).purgeExpiredPayloads();

        assertThat(purged).isEqualTo(1);
        Map<String, Object> gone = raw(oldReceived.getId());
        assertThat(gone.get("payload")).isNull();
        assertThat(gone.get("payload_purged_at")).isNotNull();
        // The row is audit evidence: its metadata is untouched.
        assertThat(gone.get("status")).isEqualTo("RECEIVED");
        assertThat(gone.get("message_type")).isEqualTo("ORU^R01");
        IntegrationMessageEvent reloaded = repository.findById(oldReceived.getId()).orElseThrow();
        assertThat(reloaded.getReceivedAt()).isEqualToIgnoringNanos(old);
        assertThat(reloaded.getErrorMessage()).isNull();
        assertThat(reloaded.getPayloadPurgedAt()).isNotNull();
        assertThat(repository.count()).isEqualTo(3);

        assertThat(raw(recentReceived.getId()).get("payload")).isNotNull();
        assertThat(repository.findById(recentReceived.getId()).orElseThrow().getPayload())
            .isEqualTo("MSH|recent");
        // A row that never had a body is not "purged".
        assertThat(raw(oldNoBody.getId()).get("payload_purged_at")).isNull();
    }

    @Test
    void anUnresolvedDeadLetterKeepsItsContentHoweverOld() {
        IntegrationMessageEvent unresolved =
            row(IntegrationMessageStatus.FAILED, "dl-1", "MSH|dead", old.minusYears(2));
        IntegrationMessageEvent noCorrelation = row(IntegrationMessageStatus.FAILED, null, "MSH|legacy", old);

        assertThat(sweep(500).purgeExpiredPayloads()).isZero();

        assertThat(repository.findById(unresolved.getId()).orElseThrow().getPayload()).isEqualTo("MSH|dead");
        assertThat(repository.findById(noCorrelation.getId()).orElseThrow().getPayload())
            .isEqualTo("MSH|legacy");
    }

    @Test
    void aResolvedDeadLetterLosesItsContentOnlyOnceTheResolutionIsPastTheWindow() {
        // Resolved long ago: replayed DAYS + 10 days back.
        IntegrationMessageEvent longResolved =
            row(IntegrationMessageStatus.FAILED, "dl-old", "MSH|a", now.minusDays(400));
        IntegrationMessageEvent itsReplay =
            row(IntegrationMessageStatus.REPLAYED, "dl-old", "MSH|a", now.minusDays(DAYS + 10L));
        // Received long ago but resolved only last week: the clock starts at resolution.
        IntegrationMessageEvent recentlyResolved =
            row(IntegrationMessageStatus.FAILED, "dl-new", "MSH|b", now.minusDays(400));
        row(IntegrationMessageStatus.REPLAYED, "dl-new", "MSH|b", now.minusDays(7));

        assertThat(sweep(500).purgeExpiredPayloads()).isEqualTo(2);

        assertThat(raw(longResolved.getId()).get("payload_purged_at")).isNotNull();
        assertThat(raw(itsReplay.getId()).get("payload_purged_at")).isNotNull();
        assertThat(repository.findById(recentlyResolved.getId()).orElseThrow().getPayload()).isEqualTo("MSH|b");
    }

    @Test
    void aRerunPurgesNothingAndKeepsTheFirstStamp() {
        IntegrationMessageEvent oldSent = row(IntegrationMessageStatus.SENT, "s-1", "body", old);
        sweep(500).purgeExpiredPayloads();
        Object firstStamp = raw(oldSent.getId()).get("payload_purged_at");
        assertThat(firstStamp).isNotNull();

        assertThat(sweep(500).purgeExpiredPayloads()).isZero();
        assertThat(raw(oldSent.getId()).get("payload_purged_at")).isEqualTo(firstStamp);
    }

    @Test
    void aBacklogLargerThanABatchIsDrainedInBatches() {
        for (int i = 0; i < 5; i++) {
            row(IntegrationMessageStatus.RECEIVED, "b-" + i, "body " + i, old.minusMinutes(i));
        }

        assertThat(sweep(2).purgeExpiredPayloads()).isEqualTo(5);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM clinical.integration_message_event WHERE payload IS NOT NULL", Long.class))
            .isZero();
    }

    @Test
    void replayOfAPurgedDeadLetterIsRefusedAndWritesNoRow() {
        IntegrationMessageEvent resolved =
            row(IntegrationMessageStatus.FAILED, "r-1", "MSH|x", now.minusDays(400));
        row(IntegrationMessageStatus.REPLAYED, "r-1", "MSH|x", now.minusDays(DAYS + 10L));
        sweep(500).purgeExpiredPayloads();
        long before = repository.count();

        assertThatThrownBy(() -> messageService.replay(resolved.getId()))
            .isInstanceOf(ConflictException.class);
        assertThat(repository.count()).isEqualTo(before);
        assertThat(messageService.getById(resolved.getId()).payloadPurgedAt()).isNotNull();
    }
}
