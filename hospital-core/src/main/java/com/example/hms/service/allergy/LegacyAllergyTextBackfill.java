package com.example.hms.service.allergy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-shot, idempotent backfill of the pre-#56 free-text allergies into the
 * structured {@code patient_allergies} table (E9 #56).
 *
 * <p>WHY a startup runner and not a migration: {@code patients.allergies} is
 * encrypted at rest by {@code EncryptedStringConverter}, so SQL cannot read
 * it. The rows are loaded through JPA (which decrypts), split by
 * {@link LegacyAllergyText}, written by {@link LegacyAllergyTextImporter},
 * and the column is then rewritten as the derived summary. Re-running is a
 * no-op: the worker skips every patient that already carries free-text
 * import rows.
 *
 * <p>The candidate query is a full constant literal: one column, one table,
 * no dynamic identifiers. Each patient runs in its own transaction; a
 * failure is logged with the patient id only and never blocks startup — a
 * boot that fails on a backfill would take the whole system down over rows
 * that are no worse off than they were yesterday. Prod runs one backend
 * instance; on a multi-instance deploy the per-patient guard plus the
 * importer's case-insensitive de-duplication keep a concurrent run from
 * producing duplicates.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "app.allergies.legacy-backfill.enabled", havingValue = "true", matchIfMissing = true)
public class LegacyAllergyTextBackfill {

    static final String CANDIDATES_SQL =
        "SELECT id FROM clinical.patients WHERE allergies IS NOT NULL AND allergies <> ''";

    private final JdbcTemplate jdbcTemplate;
    private final LegacyAllergyTextBackfillWorker worker;

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        List<UUID> candidates;
        try {
            candidates = jdbcTemplate.query(CANDIDATES_SQL, (rs, i) -> rs.getObject("id", UUID.class));
        } catch (DataAccessException ex) {
            log.error("Legacy allergy backfill could not list candidates: {}", ex.getMessage());
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }
        Map<LegacyAllergyTextBackfillWorker.Outcome, Integer> outcomes =
            new EnumMap<>(LegacyAllergyTextBackfillWorker.Outcome.class);
        int failed = 0;
        for (UUID patientId : candidates) {
            try {
                outcomes.merge(worker.backfillOne(patientId), 1, Integer::sum);
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Legacy allergy backfill failed for patient {}: {}", patientId, ex.getMessage());
            }
        }
        int imported = outcomes.getOrDefault(LegacyAllergyTextBackfillWorker.Outcome.IMPORTED, 0);
        if (imported > 0 || failed > 0) {
            log.info("Legacy allergy backfill: {} candidate(s), {} imported, {} already done, "
                    + "{} nothing to import, {} without a hospital, {} failed",
                candidates.size(), imported,
                outcomes.getOrDefault(LegacyAllergyTextBackfillWorker.Outcome.ALREADY_DONE, 0),
                outcomes.getOrDefault(LegacyAllergyTextBackfillWorker.Outcome.NOTHING_TO_IMPORT, 0),
                outcomes.getOrDefault(LegacyAllergyTextBackfillWorker.Outcome.NO_HOSPITAL, 0),
                failed);
        } else {
            log.debug("Legacy allergy backfill: {} candidate(s), nothing to import", candidates.size());
        }
    }
}
