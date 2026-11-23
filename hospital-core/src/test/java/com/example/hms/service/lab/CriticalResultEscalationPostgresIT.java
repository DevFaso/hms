package com.example.hms.service.lab;

import com.example.hms.model.CriticalResultIncident;
import com.example.hms.model.LabResult;
import com.example.hms.repository.CriticalResultIncidentRepository;
import com.example.hms.repository.LabResultRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA r70 (C5), rule CRITICAL_RESULT_ESCALATION, review of #1291 (M2, L2, L3), on a real PostgreSQL built by
 * Liquibase: the sweep's conditional UPDATEs as PostgreSQL runs them, an acknowledgement committed by another
 * transaction between the sweep's read and its write, V364's constraints on the incident register, and V364's own
 * UPDATE that marks the results already waiting.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("critical-result escalation, on PostgreSQL")
class CriticalResultEscalationPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_critical_escalation")
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

    private static final Path V364 =
        Path.of("src/main/resources/db/migration/V364__r70_critical_escalation_backlog_and_incident_end.sql");

    @Autowired private LabResultRepository results;
    @Autowired private CriticalResultIncidentRepository incidents;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    private TransactionTemplate tx;
    private TransactionTemplate anotherTx;
    private final UUID hospital = UUID.randomUUID();
    private final UUID clinician = UUID.randomUUID();

    @BeforeEach
    void seed() {
        tx = new TransactionTemplate(transactionManager);
        anotherTx = new TransactionTemplate(transactionManager);
        anotherTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            String tag = UUID.randomUUID().toString().substring(0, 8);
            entityManager.createNativeQuery("""
                INSERT INTO hospital.hospitals (id, code, name, active, created_at, updated_at)
                VALUES (:id, :code, :name, true, now(), now())
                """).setParameter("id", hospital).setParameter("code", "R70C-" + tag)
                .setParameter("name", "Hopital R70C " + tag).executeUpdate();
        });
    }

    /** A released critical result paged {@code minutesAgo} ago. The order it names is not read here. */
    private UUID paged(int minutesAgo, int level, boolean acknowledged) {
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            String at = acknowledged ? "now()" : "NULL";
            entityManager.createNativeQuery("""
                INSERT INTO lab.lab_results (id, lab_order_id, result_value, result_date, actor_type, acknowledged,
                    acknowledged_at, critical_readback_at, critical_escalation_level, critical_notified_at, released,
                    created_at, updated_at)
                VALUES (:id, :o, '7.1', now(), 'SYSTEM', :ack, %s, %s, :level, :paged, true, now(), now())
                """.formatted(at, at)).setParameter("id", id).setParameter("o", UUID.randomUUID())
                .setParameter("ack", acknowledged)
                .setParameter("level", (short) level)
                .setParameter("paged", LocalDateTime.now().minusMinutes(minutesAgo)).executeUpdate();
        });
        return id;
    }

    private Object[] row(UUID id) {
        return (Object[]) entityManager.createNativeQuery("""
            SELECT acknowledged, critical_readback_at, critical_escalation_level, critical_escalated_at,
                   critical_notified_at, critical_readback_value, critical_backlog
              FROM lab.lab_results WHERE id = :id
            """).setParameter("id", id).getSingleResult();
    }

    private List<LabResult> open() {
        return tx.execute(status -> results.findCriticalUnacknowledged());
    }

    private int leave(UUID id) {
        return tx.execute(status -> results.leaveCriticalChainIfUnacknowledged(id, (short) 0));
    }

    private int stamp(UUID id, int expected, int level, LocalDateTime at) {
        return tx.execute(status -> results.stampEscalationIfUnacknowledged(id, (short) expected, (short) level, at));
    }

    @Test
    @DisplayName("review M2: a step is stamped only on an unacknowledged row standing at the level the sweep read, and writes the two stamps only")
    void theStampIsConditional() {
        UUID waiting = paged(20, 0, false);
        LocalDateTime at = LocalDateTime.now().withNano(0);

        assertThat(stamp(waiting, 0, 1, at)).isEqualTo(1);
        Object[] stamped = row(waiting);
        assertThat(stamped[0]).isEqualTo(false);
        assertThat(((Number) stamped[2]).intValue()).isEqualTo(1);
        assertThat(stamped[3]).isNotNull();

        // Already taken: a second sweep that read level 0 changes nothing.
        assertThat(stamp(waiting, 0, 1, at)).isZero();
        // Given back (a step that could not be told): the level and a null stamp are written the same way.
        assertThat(stamp(waiting, 1, 0, null)).isEqualTo(1);
        assertThat(((Number) row(waiting)[2]).intValue()).isZero();
        assertThat(row(waiting)[3]).isNull();

        UUID acknowledged = paged(20, 0, true);
        assertThat(stamp(acknowledged, 0, 1, at)).isZero();
        assertThat(((Number) row(acknowledged)[2]).intValue()).isZero();
        assertThat(row(acknowledged)[0]).isEqualTo(true);
        assertThat(stamp(UUID.randomUUID(), 0, 1, at)).isZero();
    }

    @Test
    @DisplayName("review M2: a read-back committed by another transaction after the sweep read the row stands: the sweep's write finds nothing and writes nothing over it")
    void aReadBackCommittedAfterTheSweepReadTheRowStands() {
        UUID id = paged(20, 0, false);

        Integer stamped = tx.execute(status -> {
            // The sweep reads its rows: unacknowledged, level 0. The entity stays loaded in this transaction.
            List<LabResult> open = results.findCriticalUnacknowledged();
            LabResult loaded = open.stream().filter(result -> result.getId().equals(id)).findFirst().orElseThrow();
            assertThat(loaded.isAcknowledged()).isFalse();

            // Meanwhile a clinician reads the value back, in a transaction of their own, and it commits.
            Integer confirmed = anotherTx.execute(other -> results.confirmReadBackIfUnconfirmed(id, "7.1", clinician,
                "Dr Awa Garde", LocalDateTime.now()));
            assertThat(confirmed).isEqualTo(1);

            // The sweep reaches the row with what it read: the conditional UPDATE sees the acknowledgement.
            return results.stampEscalationIfUnacknowledged(id, loaded.getCriticalEscalationLevel(), (short) 1,
                LocalDateTime.now());
        });

        assertThat(stamped).isZero();
        Object[] after = row(id);
        assertThat(after[0]).as("acknowledged is not written back to false").isEqualTo(true);
        assertThat(after[1]).as("the read-back time stands").isNotNull();
        assertThat(after[5]).isEqualTo("7.1");
        assertThat(((Number) after[2]).intValue()).as("no step was taken").isZero();
        assertThat(after[3]).isNull();
        // And the result is out of the sweep for good.
        assertThat(open()).extracting(LabResult::getId).doesNotContain(id);
    }

    @Test
    @DisplayName("QA D305: a chain is left by a conditional UPDATE too: stamps cleared on an unacknowledged row, nothing on an acknowledged one")
    void leavingTheChainIsConditional() {
        UUID noLongerCritical = paged(90, 3, false);
        UUID acknowledged = paged(90, 3, true);

        assertThat(leave(noLongerCritical)).isEqualTo(1);
        assertThat(leave(acknowledged)).isZero();

        Object[] left = row(noLongerCritical);
        assertThat(left[4]).isNull();
        assertThat(left[3]).isNull();
        assertThat(((Number) left[2]).intValue()).isZero();
        Object[] kept = row(acknowledged);
        assertThat(kept[4]).as("an acknowledged result keeps the record of its page").isNotNull();
        assertThat(((Number) kept[2]).intValue()).isEqualTo(3);
    }

    private CriticalResultIncident incident(UUID resultId, String resolution) {
        LocalDateTime now = LocalDateTime.now();
        return CriticalResultIncident.builder().labResultId(resultId).hospitalId(hospital).openedAt(now.minusHours(2))
            .minutesUnacknowledged(61).careSetting("INPATIENT").recipientsTold(3)
            .resolvedAt(resolution == null ? null : now).resolution(resolution).build();
    }

    private void save(CriticalResultIncident incident) {
        tx.executeWithoutResult(status -> incidents.saveAndFlush(incident));
    }

    @Test
    @DisplayName("review L2 (V364): one open incident per result and any number of closed ones; a closed incident says how it ended, in one of the three ways")
    void theIncidentRegister() {
        UUID result = paged(90, 3, false);

        save(incident(result, CriticalResultIncident.RESOLVED_AMENDED));
        save(incident(result, CriticalResultIncident.RESOLVED_NO_LONGER_CRITICAL));
        save(incident(result, CriticalResultIncident.RESOLVED_READ_BACK));
        save(incident(result, null));

        assertThat(incidents.findByLabResultIdOrderByOpenedAtDesc(result)).hasSize(4);
        assertThat(incidents.findByLabResultIdAndResolvedAtIsNull(result)).isPresent();
        // A second OPEN incident of the same result is refused by the table itself.
        CriticalResultIncident secondOpen = incident(result, null);
        assertThatThrownBy(() -> save(secondOpen)).isInstanceOf(DataIntegrityViolationException.class);

        // A closed incident with no reason, a reason the rule does not know, and a reason on an open one.
        CriticalResultIncident noReason = incident(result, CriticalResultIncident.RESOLVED_AMENDED);
        noReason.setResolution(null);
        assertThatThrownBy(() -> save(noReason)).isInstanceOf(DataIntegrityViolationException.class);
        CriticalResultIncident unknown = incident(result, "DISMISSED");
        assertThatThrownBy(() -> save(unknown)).isInstanceOf(DataIntegrityViolationException.class);
        CriticalResultIncident openWithReason = incident(paged(90, 3, false), CriticalResultIncident.RESOLVED_AMENDED);
        openWithReason.setResolvedAt(null);
        assertThatThrownBy(() -> save(openWithReason)).isInstanceOf(DataIntegrityViolationException.class);

        // Closing the open one makes room for the next.
        tx.executeWithoutResult(status -> {
            CriticalResultIncident open = incidents.findByLabResultIdAndResolvedAtIsNull(result).orElseThrow();
            open.setResolvedAt(LocalDateTime.now());
            open.setResolution(CriticalResultIncident.RESOLVED_AMENDED);
            incidents.saveAndFlush(open);
        });
        save(incident(result, null));
        assertThat(incidents.findByLabResultIdOrderByOpenedAtDesc(result)).hasSize(5);
    }

    @Test
    @DisplayName("review L3 (V364): the migration's own UPDATE marks the results waiting at that moment, and only those; a result paged afterwards is not marked")
    void theBacklogMark() throws Exception {
        UUID waiting = paged(3 * 24 * 60, 2, false);
        UUID acknowledged = paged(3 * 24 * 60, 2, true);
        UUID neverPaged = paged(10, 0, false);
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(
            "UPDATE lab.lab_results SET critical_notified_at = NULL WHERE id = :id").setParameter("id", neverPaged)
            .executeUpdate());
        // New rows are not marked: the column defaults to false.
        assertThat(row(waiting)[6]).isEqualTo(false);

        String migration = Files.readString(V364, StandardCharsets.UTF_8);
        int from = migration.indexOf("UPDATE lab.lab_results");
        String markTheBacklog = migration.substring(from, migration.indexOf(';', from));
        assertThat(markTheBacklog).contains("critical_backlog = TRUE");
        tx.executeWithoutResult(status -> entityManager.createNativeQuery(markTheBacklog).executeUpdate());

        assertThat(row(waiting)[6]).isEqualTo(true);
        assertThat(row(acknowledged)[6]).isEqualTo(false);
        assertThat(row(neverPaged)[6]).isEqualTo(false);
        UUID pagedAfterwards = paged(1, 0, false);
        assertThat(row(pagedAfterwards)[6]).isEqualTo(false);
        // The entity reads the mark.
        assertThat(open()).filteredOn(LabResult::isCriticalBacklog)
            .extracting(LabResult::getId).contains(waiting).doesNotContain(pagedAfterwards);
    }
}
