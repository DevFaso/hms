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

    private final List<UUID> identityIds = new ArrayList<>();
    private final List<UUID> mergeEventIds = new ArrayList<>();
    private final List<UUID> messageEventIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        mergeEventRepository.deleteAllByIdInBatch(mergeEventIds);
        identityRepository.deleteAllByIdInBatch(identityIds);
        messageEventRepository.deleteAllByIdInBatch(messageEventIds);
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
}
