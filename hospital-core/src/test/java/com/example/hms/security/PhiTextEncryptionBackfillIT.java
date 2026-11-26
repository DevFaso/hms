package com.example.hms.security;

import com.example.hms.BaseIT;
import com.example.hms.enums.empi.EmpiMergeType;
import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.integration.IntegrationMessageEvent;
import com.example.hms.model.empi.EmpiMasterIdentity;
import com.example.hms.model.empi.EmpiMergeEvent;
import com.example.hms.repository.empi.EmpiMasterIdentityRepository;
import com.example.hms.repository.empi.EmpiMergeEventRepository;
import com.example.hms.repository.integration.IntegrationMessageEventRepository;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup backfill that encrypts legacy plaintext in columns which gained
 * {@code EncryptedStringConverter} after rows were written to them.
 *
 * <p>A legacy row is simulated the only honest way: written through the
 * entity (so every other column is real), then its column set back to
 * plaintext by SQL, exactly what a row from before the converter looks like.
 *
 * <p>Same context shape as {@code AdtA40MergeEndToEndIT} ({@code BaseIT} +
 * {@code @AutoConfigureMockMvc(addFilters = false)}), so it adds no context to
 * the capped CI heap. It deletes only the rows it created.
 */
@AutoConfigureMockMvc(addFilters = false)
class PhiTextEncryptionBackfillIT extends BaseIT {

    @Autowired private PhiTextEncryptionBackfill backfill;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EmpiMasterIdentityRepository identityRepository;
    @Autowired private EmpiMergeEventRepository mergeEventRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private IntegrationMessageEventRepository messageEventRepository;
    @Autowired private IntegrationMessageRecorder messageRecorder;
    @Autowired private com.example.hms.repository.NotificationRepository notificationRepository;
    @Autowired private com.example.hms.service.NotificationService notificationService;

    private final List<UUID> notificationIds = new ArrayList<>();

    private final List<UUID> identityIds = new ArrayList<>();
    private final List<UUID> mergeEventIds = new ArrayList<>();
    private final List<UUID> messageEventIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        mergeEventRepository.deleteAllByIdInBatch(mergeEventIds);
        identityRepository.deleteAllByIdInBatch(identityIds);
        messageEventRepository.deleteAllByIdInBatch(messageEventIds);
        notificationRepository.deleteAllByIdInBatch(notificationIds);
        notificationIds.clear();
        mergeEventIds.clear();
        identityIds.clear();
        messageEventIds.clear();
    }

    @Test
    @DisplayName("a legacy plaintext integration payload is encrypted at rest and still reads back whole")
    void aLegacyPayloadIsEncrypted() {
        String legacy = "MSH|^~\\&|LIS|HOSP1|HMS|HMS|20260926||ADT^A08|M-" + UUID.randomUUID()
            + "|P|2.5\rPID|1||BKF-MRN^^^HOSP^MR||Traore^Awa||19900101|F\r";
        IntegrationMessageEvent row = messageRecorder.recordMessage("MLLP:BKF/IT", null,
            IntegrationMessageDirection.INBOUND, "ADT^A08", "to be replaced",
            IntegrationMessageStatus.FAILED, "unparseable");
        messageEventIds.add(row.getId());
        jdbcTemplate.update("UPDATE clinical.integration_message_event SET payload = ? WHERE id = ?",
            legacy, row.getId());

        backfill.backfill();

        assertThat(storedPayload(row.getId())).startsWith("gcm1:").doesNotContain("Traore");
        assertThat(messageEventRepository.findById(row.getId()).orElseThrow().getPayload()).isEqualTo(legacy);
    }

    @Test
    @DisplayName("a payload recorded now is ciphertext at rest, and a recurring failure still folds into its row")
    void aRecordedPayloadIsEncryptedAndStillFolds() {
        String correlationId = "bkf-" + UUID.randomUUID();
        IntegrationMessageEvent first = messageRecorder.recordRecurringFailure("MLLP:BKF/IT", null,
            IntegrationMessageDirection.INBOUND, "ADT^A08", "MSH|first\rPID|1||X||Traore^Awa\r",
            "unparseable ADT^A08", correlationId);
        messageEventIds.add(first.getId());

        IntegrationMessageEvent retry = messageRecorder.recordRecurringFailure("MLLP:BKF/IT", null,
            IntegrationMessageDirection.INBOUND, "ADT^A08", "MSH|latest\rPID|1||X||Traore^Awa\r",
            "unparseable ADT^A08", correlationId);
        messageEventIds.add(retry.getId());

        // The fold keys on the correlation id, never on the payload, so it
        // still engages with an encrypted column: one row, two attempts.
        assertThat(retry.getId()).isEqualTo(first.getId());
        IntegrationMessageEvent folded = messageEventRepository.findById(first.getId()).orElseThrow();
        assertThat(folded.getAttemptCount()).isEqualTo(2);
        assertThat(folded.getPayload()).startsWith("MSH|latest");
        assertThat(storedPayload(first.getId())).startsWith("gcm1:").doesNotContain("Traore");
    }

    @Test
    @DisplayName("a legacy plaintext merge note is encrypted at rest, still reads back whole, and a rerun changes nothing")
    void aLegacyMergeNoteIsEncrypted() {
        String legacy = "HL7 ADT^A40 from LIS/HOSP1: MRN BKF-PRIOR-" + UUID.randomUUID()
            + " merged into BKF-SURV (MSH-10 M1)";
        UUID eventId = mergeEventWithPlaintextNotes(legacy);
        assertThat(storedNotes(eventId)).isEqualTo(legacy);

        backfill.backfill();

        String encrypted = storedNotes(eventId);
        assertThat(encrypted).startsWith("gcm1:").doesNotContain("BKF-PRIOR");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            assertThat(mergeEventRepository.findTopBySecondaryIdentity_IdOrderByMergedAtDesc(
                identityIds.get(1)).orElseThrow().getNotes()).isEqualTo(legacy));

        backfill.backfill();

        assertThat(storedNotes(eventId)).as("idempotent: ciphertext is never re-encrypted")
            .isEqualTo(encrypted);
    }

    @Test
    @DisplayName("null and empty notes are left alone")
    void blankNotesAreLeftAlone() {
        UUID empty = mergeEventWithPlaintextNotes("");
        UUID none = mergeEventWithPlaintextNotes(null);

        backfill.backfill();

        assertThat(storedNotes(empty)).isEmpty();
        assertThat(storedNotes(none)).isNull();
    }

    private UUID mergeEventWithPlaintextNotes(String notes) {
        EmpiMasterIdentity primary = identity();
        EmpiMasterIdentity secondary = identity();
        EmpiMergeEvent event = mergeEventRepository.save(EmpiMergeEvent.builder()
            .primaryIdentity(primary)
            .secondaryIdentity(secondary)
            .mergeType(EmpiMergeType.AUTOMATED)
            .notes("to be replaced")
            .build());
        mergeEventIds.add(event.getId());
        jdbcTemplate.update("UPDATE empi.merge_events SET notes = ? WHERE id = ?", notes, event.getId());
        return event.getId();
    }

    private EmpiMasterIdentity identity() {
        EmpiMasterIdentity saved = identityRepository.save(EmpiMasterIdentity.builder()
            .empiNumber("EMP-BKF-" + UUID.randomUUID())
            .sourceSystem("BACKFILL-IT")
            .build());
        identityIds.add(saved.getId());
        return saved;
    }

    private String storedPayload(UUID eventId) {
        return jdbcTemplate.queryForObject(
            "SELECT payload FROM clinical.integration_message_event WHERE id = ?", String.class, eventId);
    }

    private String storedNotes(UUID eventId) {
        return jdbcTemplate.queryForObject(
            "SELECT notes FROM empi.merge_events WHERE id = ?", String.class, eventId);
    }

    // ---------- V387 (scorecard K4): the in-app notification ----------

    private com.example.hms.model.Notification notification(String recipient, String body, List<Object> args) {
        com.example.hms.model.Notification saved = notificationRepository.save(
            com.example.hms.model.Notification.builder().message(body).messageKey("k4b.test.key").messageArgs(args)
                .recipientUsername(recipient).type("CRITICAL_LAB_RESULT")
                .createdAt(java.time.LocalDateTime.now()).read(false).build());
        notificationIds.add(saved.getId());
        return saved;
    }

    private String storedBody(UUID id) {
        return jdbcTemplate.queryForObject("SELECT message FROM security.notifications WHERE id = ?", String.class, id);
    }

    private String storedArgs(UUID id) {
        return jdbcTemplate.queryForObject(
            "SELECT CAST(message_args AS TEXT) FROM security.notifications WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("a notification written now is ciphertext at rest (body and arguments), reads back whole and is still searchable")
    void aNotificationWrittenNowIsCiphertextAtRest() {
        String recipient = "k4b-" + UUID.randomUUID();
        String body = "Résultat critique : Potassium 6,8 mmol/L pour Traore Awa. ".repeat(6);
        List<Object> args = List.of(7, "Traore Awa", java.util.Map.of("key", "lab.test.potassium"));
        UUID id = notification(recipient, body, args).getId();

        assertThat(body.length()).as("longer than the column once held").isGreaterThan(255);
        assertThat(storedBody(id)).startsWith("gcm1:").doesNotContain("Traore", "Potassium");
        assertThat(storedArgs(id)).contains("sealed", "gcm1:").doesNotContain("Traore", "potassium");

        com.example.hms.model.Notification read = notificationRepository.findById(id).orElseThrow();
        assertThat(read.getMessage()).isEqualTo(body);
        assertThat(read.getMessageArgs()).isEqualTo(args);

        assertThat(notificationService.getNotificationsForUser(recipient, null, "POTASSIUM",
            org.springframework.data.domain.PageRequest.of(0, 10)).getContent())
            .extracting(com.example.hms.model.Notification::getId).containsExactly(id);
        assertThat(notificationService.getNotificationsForUser(recipient, false, "sodium",
            org.springframework.data.domain.PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    @Test
    @DisplayName("a notification written before is read as it is, then encrypted by the backfill, once")
    void aNotificationWrittenBeforeIsEncryptedByTheBackfill() {
        UUID id = notification("k4b-" + UUID.randomUUID(), "to be replaced", List.of("x")).getId();
        UUID bare = notification("k4b-" + UUID.randomUUID(), "to be replaced", null).getId();
        String legacyBody = "Résultat critique : Potassium 6,8 mmol/L pour Traore Awa";
        jdbcTemplate.update("UPDATE security.notifications SET message = ?,"
            + " message_args = CAST(? AS jsonb) WHERE id = ?", legacyBody, "[7, \"Traore Awa\", {\"key\": \"a.b\"}]", id);
        jdbcTemplate.update("UPDATE security.notifications SET message = ?, message_args = NULL WHERE id = ?",
            legacyBody, bare);
        List<Object> legacyArgs = List.of(7, "Traore Awa", java.util.Map.of("key", "a.b"));

        com.example.hms.model.Notification before = notificationRepository.findById(id).orElseThrow();
        assertThat(before.getMessage()).isEqualTo(legacyBody);
        assertThat(before.getMessageArgs()).isEqualTo(legacyArgs);

        backfill.backfill();

        String body = storedBody(id);
        String args = storedArgs(id);
        assertThat(body).startsWith("gcm1:").doesNotContain("Traore");
        assertThat(args).contains("sealed").doesNotContain("Traore");
        assertThat(storedBody(bare)).startsWith("gcm1:");
        assertThat(storedArgs(bare)).isNull();
        com.example.hms.model.Notification after = notificationRepository.findById(id).orElseThrow();
        assertThat(after.getMessage()).isEqualTo(legacyBody);
        assertThat(after.getMessageArgs()).isEqualTo(legacyArgs);

        backfill.backfill();

        assertThat(storedBody(id)).as("idempotent").isEqualTo(body);
        assertThat(storedArgs(id)).as("idempotent").isEqualTo(args);
    }
}
