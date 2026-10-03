package com.example.hms.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Encrypts, at startup, the legacy plaintext values of text columns that
 * gained {@code @Convert(converter = EncryptedStringConverter.class)} after
 * rows were already written to them.
 *
 * <p>WHY: the converter encrypts FUTURE writes only - it deliberately returns
 * a value without the {@code gcm1:} prefix verbatim, so rows written before the
 * annotation existed would stay plaintext at rest for as long as nobody
 * rewrote them (the item-45 lesson; see
 * {@code CredentialReferenceEncryptionBackfill}, the first of these). This
 * finds every legacy value, encrypts it with the configured key and writes the
 * ciphertext back.
 *
 * <p>Idempotent and safe to run on every start and on several instances at
 * once: a row is selected only while it has no {@code gcm1:} prefix, and the
 * UPDATE repeats that condition, so an instance that loses a race writes
 * nothing, and a row the application re-wrote in between (already through the
 * converter) is never overwritten with an older value.
 *
 * <p><b>Off the readiness path.</b> The listener only starts a background
 * thread and returns, so a large {@code integration_message_event} backlog on
 * prod never holds the application's readiness: Spring Boot publishes
 * ACCEPTING_TRAFFIC after the ApplicationReadyEvent listeners return, and this
 * one returns at once.
 *
 * <p>Batched: up to {@value #BATCH_SIZE} rows per transaction (one SELECT page,
 * one JDBC batch of conditional UPDATEs, one commit), so progress survives a
 * restart, {@code payload}'s up-to-64-KB rows are never loaded all at once, and
 * a batch in which nothing could be updated ends that column's pass rather
 * than looping on the same rows. Progress is logged as counts only - never a
 * value, an id or anything read from a row.
 *
 * <p>Every SQL statement is a full constant literal - known tables, no dynamic
 * identifiers, values bound as parameters.
 *
 * <p>Skips quietly when no key is configured; logs loudly on a failure but
 * never blocks startup - a boot that fails on a backfill would take the whole
 * system down over rows that are no worse off than they were yesterday.
 *
 * <p>Known edge, shared with every converted column: a legacy plaintext value
 * that itself begins with {@code gcm1:} is taken for ciphertext, skipped here
 * and fails to decrypt on read.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PhiTextEncryptionBackfill {

    private record Target(String label, String selectSql, String updateSql) {
    }

    /** One entry per converted column. Each SELECT pages by its literal LIMIT. */
    private static final List<Target> TARGETS = List.of(
        new Target("empi.merge_events.notes",
            "SELECT id, notes AS val FROM empi.merge_events"
                + " WHERE notes IS NOT NULL AND notes <> '' AND notes NOT LIKE 'gcm1:%' LIMIT 500",
            "UPDATE empi.merge_events SET notes = ?"
                + " WHERE id = ? AND notes NOT LIKE 'gcm1:%'"),
        new Target("clinical.integration_message_event.payload",
            "SELECT id, payload AS val FROM clinical.integration_message_event"
                + " WHERE payload IS NOT NULL AND payload <> '' AND payload NOT LIKE 'gcm1:%' LIMIT 500",
            "UPDATE clinical.integration_message_event SET payload = ?"
                + " WHERE id = ? AND payload NOT LIKE 'gcm1:%'"));

    /** Rows per transaction; the SELECTs' literal LIMIT says the same. */
    static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;

    private final EncryptedStringConverter converter = new EncryptedStringConverter();

    /** Starts the backfill on its own thread and returns: never on the readiness path. */
    @EventListener(ApplicationReadyEvent.class)
    public void startAfterReady() {
        Thread worker = new Thread(this::backfill, "phi-text-encryption-backfill");
        worker.setDaemon(true);
        worker.start();
    }

    /** The whole pass, synchronously, on the calling thread. */
    public void backfill() {
        if (EncryptionKeyHolder.getKey() == null) {
            log.info("PHI text encryption backfill skipped: no encryption key configured");
            return;
        }
        for (Target target : TARGETS) {
            try {
                int updated = backfillTarget(target);
                if (updated > 0) {
                    log.info("Encrypted {} legacy {} value(s) at rest", updated, target.label());
                }
            } catch (RuntimeException ex) {
                log.error("PHI text encryption backfill failed for {}: {}",
                    target.label(), ex.getMessage(), ex);
            }
        }
    }

    /** One legacy row: its id and its plaintext, read as a String (a TEXT column may be a CLOB). */
    record LegacyValue(Object id, String value) {
    }

    private int backfillTarget(Target target) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        int total = 0;
        int batches = 0;
        while (true) {
            Integer updated = tx.execute(status -> encryptOneBatch(target));
            int done = updated == null ? 0 : updated;
            if (done == 0) {
                return total;
            }
            total += done;
            batches++;
            log.info("PHI text encryption backfill: {} batch {} done, {} value(s) encrypted so far",
                target.label(), batches, total);
        }
    }

    /** One page, encrypted and written back in one transaction; the number of rows actually updated. */
    private int encryptOneBatch(Target target) {
        List<LegacyValue> rows = jdbcTemplate.query(target.selectSql(),
            (rs, rowNum) -> new LegacyValue(rs.getObject("id"), rs.getString("val")));
        if (rows.isEmpty()) {
            return 0;
        }
        List<Object[]> args = rows.stream()
            .map(row -> new Object[] {converter.convertToDatabaseColumn(row.value()), row.id()})
            .toList();
        int updated = 0;
        for (int count : jdbcTemplate.batchUpdate(target.updateSql(), args)) {
            // A driver may report SUCCESS_NO_INFO (-2) for a batched row.
            updated += count == java.sql.Statement.SUCCESS_NO_INFO ? 1 : Math.max(count, 0);
        }
        return updated;
    }
}
