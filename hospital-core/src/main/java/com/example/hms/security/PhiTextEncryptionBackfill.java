package com.example.hms.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
 * <p>Paged, and not one transaction: {@code integration_message_event.payload}
 * holds up to 64 KB per row, so the whole backlog is never loaded at once, and
 * each UPDATE commits on its own so progress survives a restart. A page in
 * which nothing could be updated ends that column's pass rather than looping
 * on the same rows.
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
                + " WHERE notes IS NOT NULL AND notes <> '' AND notes NOT LIKE 'gcm1:%' LIMIT 200",
            "UPDATE empi.merge_events SET notes = ?"
                + " WHERE id = ? AND notes NOT LIKE 'gcm1:%'"),
        new Target("clinical.integration_message_event.payload",
            "SELECT id, payload AS val FROM clinical.integration_message_event"
                + " WHERE payload IS NOT NULL AND payload <> '' AND payload NOT LIKE 'gcm1:%' LIMIT 200",
            "UPDATE clinical.integration_message_event SET payload = ?"
                + " WHERE id = ? AND payload NOT LIKE 'gcm1:%'"));

    private final JdbcTemplate jdbcTemplate;

    private final EncryptedStringConverter converter = new EncryptedStringConverter();

    @EventListener(ApplicationReadyEvent.class)
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
    private record LegacyValue(Object id, String value) {
    }

    private int backfillTarget(Target target) {
        int total = 0;
        while (true) {
            List<LegacyValue> rows = jdbcTemplate.query(target.selectSql(),
                (rs, rowNum) -> new LegacyValue(rs.getObject("id"), rs.getString("val")));
            int updated = 0;
            for (LegacyValue row : rows) {
                String cipherText = converter.convertToDatabaseColumn(row.value());
                updated += jdbcTemplate.update(target.updateSql(), cipherText, row.id());
            }
            total += updated;
            if (rows.isEmpty() || updated == 0) {
                return total;
            }
        }
    }
}
