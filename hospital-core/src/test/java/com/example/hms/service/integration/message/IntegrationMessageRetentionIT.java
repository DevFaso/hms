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
    private static final int UNRESOLVED_MAX_DAYS = 365;

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
    @Autowired
    private IntegrationMessageRecorder recorder;

    private final LocalDateTime now = LocalDateTime.now();
    private final LocalDateTime old = now.minusDays(DAYS + 30L);

    @BeforeEach
    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    private IntegrationMessageRetentionScheduler sweep(int batchSize) {
        return new IntegrationMessageRetentionScheduler(
            retentionService, new IntegrationMessageRetentionPolicy(true, DAYS, UNRESOLVED_MAX_DAYS),
            Clock.systemDefaultZone(), batchSize, 200);
    }

    private IntegrationMessageEvent row(IntegrationMessageStatus status, String correlationId,
                                        String payload, LocalDateTime at) {
        return row("MLLP:LAB|SITE", status, correlationId, payload, at);
    }

    private IntegrationMessageEvent row(String integrationId, IntegrationMessageStatus status,
                                        String correlationId, String payload, LocalDateTime at) {
        return repository.saveAndFlush(IntegrationMessageEvent.builder()
            .integrationId(integrationId)
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
        // Content gone, stamp set; the row is audit evidence, so its metadata is untouched.
        assertThat(gone)
            .containsEntry("payload", null)
            .doesNotContainEntry("payload_purged_at", null)
            .containsEntry("status", "RECEIVED")
            .containsEntry("message_type", "ORU^R01");
        IntegrationMessageEvent reloaded = repository.findById(oldReceived.getId()).orElseThrow();
        assertThat(reloaded.getReceivedAt()).isEqualToIgnoringNanos(old);
        assertThat(reloaded.getErrorMessage()).isNull();
        assertThat(reloaded.getPayloadPurgedAt()).isNotNull();
        assertThat(repository.count()).isEqualTo(3);

        assertThat(raw(recentReceived.getId()).get("payload")).isNotNull();
        assertThat(repository.findById(recentReceived.getId()).orElseThrow().getPayload())
            .isEqualTo("MSH|recent");
        // A row that never had a body is not "purged".
        assertThat(raw(oldNoBody.getId())).containsEntry("payload_purged_at", null);
    }

    @Test
    void anUnresolvedDeadLetterKeepsItsContentPastTheWindowButNotPastTheCeiling() {
        IntegrationMessageEvent at200 = row(IntegrationMessageStatus.FAILED, "dl-200", "MSH|200", now.minusDays(200));
        IntegrationMessageEvent at370 = row(IntegrationMessageStatus.FAILED, "dl-370", "MSH|370", now.minusDays(370));
        IntegrationMessageEvent noCorrelation =
            row(IntegrationMessageStatus.FAILED, null, "MSH|legacy", now.minusDays(370));

        assertThat(sweep(500).purgeExpiredPayloads()).isEqualTo(2);

        // Still replayable inside the ceiling...
        assertThat(repository.findById(at200.getId()).orElseThrow().getPayload()).isEqualTo("MSH|200");
        // ...but nothing is kept for ever, including a row that can never be resolved.
        assertThat(raw(at370.getId())).containsEntry("payload", null);
        assertThat(raw(at370.getId()).get("payload_purged_at")).isNotNull();
        assertThat(raw(noCorrelation.getId())).containsEntry("payload", null);
    }

    @Test
    void aRejectFromAnUnknownSenderCannotBeResolvedSoTheCeilingIsWhatErasesIt() {
        // The dispatcher records a sender it could not resolve with a random
        // correlation id, so no later row can ever supersede it. Before the
        // ceiling, its raw message stayed for ever.
        IntegrationMessageEvent kept = row("MLLP:?|?", IntegrationMessageStatus.FAILED,
            UUID.randomUUID().toString(), "MSH|anon-1", now.minusDays(200));
        IntegrationMessageEvent erased = row("MLLP:?|?", IntegrationMessageStatus.FAILED,
            UUID.randomUUID().toString(), "MSH|anon-2", now.minusDays(370));

        assertThat(sweep(500).purgeExpiredPayloads()).isEqualTo(1);

        assertThat(repository.findById(kept.getId()).orElseThrow().getPayload()).isEqualTo("MSH|anon-1");
        assertThat(raw(erased.getId())).containsEntry("payload", null);
    }

    @Test
    void aResolvedDeadLetterLosesItsContentOnlyOnceTheResolutionIsPastTheWindow() {
        // Resolved long ago: replayed DAYS + 10 days back.
        IntegrationMessageEvent longResolved =
            row(IntegrationMessageStatus.FAILED, "dl-old", "MSH|a", now.minusDays(400));
        IntegrationMessageEvent itsReplay =
            row(IntegrationMessageStatus.REPLAYED, "dl-old", "MSH|a", now.minusDays(DAYS + 10L));
        // Received 300 days ago (inside the ceiling) but resolved only last
        // week: the resolution clock starts at resolution.
        IntegrationMessageEvent recentlyResolved =
            row(IntegrationMessageStatus.FAILED, "dl-new", "MSH|b", now.minusDays(300));
        row(IntegrationMessageStatus.REPLAYED, "dl-new", "MSH|b", now.minusDays(7));

        assertThat(sweep(500).purgeExpiredPayloads()).isEqualTo(2);

        assertThat(raw(longResolved.getId()).get("payload_purged_at")).isNotNull();
        assertThat(raw(itsReplay.getId()).get("payload_purged_at")).isNotNull();
        assertThat(repository.findById(recentlyResolved.getId()).orElseThrow().getPayload()).isEqualTo("MSH|b");
    }

    @Test
    void theCeilingIsAbsoluteEvenForARowSupersededByAStillRetryingOne() {
        // A newer FAILED row on the same correlation id keeps absorbing
        // retries, so every fold refreshes its lastAttemptedAt and the old
        // row is never "resolved before the cutoff". Without an absolute
        // ceiling its body would be kept as long as the feed keeps failing.
        IntegrationMessageEvent superseded =
            row(IntegrationMessageStatus.FAILED, "storm", "MSH|first", now.minusDays(370));
        IntegrationMessageEvent stillRetrying =
            row(IntegrationMessageStatus.FAILED, "storm", "MSH|latest", now.minusMinutes(5));
        // The same shape inside the ceiling is kept.
        IntegrationMessageEvent supersededRecent =
            row(IntegrationMessageStatus.FAILED, "storm-2", "MSH|r", now.minusDays(300));
        row(IntegrationMessageStatus.FAILED, "storm-2", "MSH|r2", now.minusMinutes(5));

        assertThat(sweep(500).purgeExpiredPayloads()).isEqualTo(1);

        assertThat(raw(superseded.getId())).containsEntry("payload", null);
        assertThat(repository.findById(stillRetrying.getId()).orElseThrow().getPayload())
            .isEqualTo("MSH|latest");
        assertThat(repository.findById(supersededRecent.getId()).orElseThrow().getPayload())
            .isEqualTo("MSH|r");
    }

    @Test
    void aRerunPurgesNothingAndKeepsTheFirstStamp() {
        IntegrationMessageEvent oldSent = row(IntegrationMessageStatus.SENT, "s-1", "body", old);
        sweep(500).purgeExpiredPayloads();
        Object firstStamp = raw(oldSent.getId()).get("payload_purged_at");
        assertThat(firstStamp).isNotNull();

        assertThat(sweep(500).purgeExpiredPayloads()).isZero();
        assertThat(raw(oldSent.getId())).containsEntry("payload_purged_at", firstStamp);
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

        UUID resolvedId = resolved.getId();
        assertThatThrownBy(() -> messageService.replay(resolvedId))
            .isInstanceOf(ConflictException.class);
        assertThat(repository.count()).isEqualTo(before);
        assertThat(messageService.getById(resolved.getId()).payloadPurgedAt()).isNotNull();
    }

    @Test
    void aSweepBetweenTheCheckAndTheWriteIsSeenUnderTheLock() {
        // The service's purge check has already passed (the row held content
        // when it read it); the sweep then erases the content before the
        // replay's write. The write re-reads the row under its lock, sees the
        // purge and refuses - no REPLAYED row, no copy of erased content.
        IntegrationMessageEvent resolved =
            row(IntegrationMessageStatus.FAILED, "race-1", "MSH|y", now.minusDays(400));
        UUID resolvedId = resolved.getId();
        sweep(500).purgeExpiredPayloads();
        long before = repository.count();

        assertThatThrownBy(() -> recorder.recordReplay(resolvedId, IntegrationMessageStatus.REPLAYED, null))
            .isInstanceOf(ConflictException.class);
        assertThat(repository.count()).isEqualTo(before);
    }
}
