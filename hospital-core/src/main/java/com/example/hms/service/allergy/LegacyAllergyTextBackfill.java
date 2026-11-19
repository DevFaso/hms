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
 * no-op: every patient processed is recorded in the import ledger
 * ({@link LegacyAllergyImportLedger}, V315) in the same transaction, and the
 * candidate query skips them (QA D2220: done-ness used to be inferred from
 * the rows, which re-ran every patient whose text matched hand-recorded rows
 * on every start).
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

    /**
     * QA D2220: a patient the ledger (V315) has recorded is never a candidate
     * again, so the import runs at most once per patient.
     */
    static final String CANDIDATES_SQL =
        "SELECT p.id FROM clinical.patients p WHERE p.allergies IS NOT NULL AND p.allergies <> ''"
            + " AND NOT EXISTS (SELECT 1 FROM clinical.patient_allergy_legacy_imports l WHERE l.patient_id = p.id)";

    private final JdbcTemplate jdbcTemplate;
    private final LegacyAllergyTextBackfillWorker worker;

    /**
     * What one pass did, per outcome, in patients. {@code imported} counts
     * only patients for whom at least one row was created (QA D2220).
     */
    public record Summary(int candidates, Map<LegacyAllergyTextBackfillWorker.Outcome, Integer> outcomes, int failed) {

        public int count(LegacyAllergyTextBackfillWorker.Outcome outcome) {
            return outcomes.getOrDefault(outcome, 0);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        pass();
    }

    /** The whole pass, synchronously; {@code null} when the candidates could not be listed. */
    public Summary pass() {
        List<UUID> candidates;
        try {
            candidates = jdbcTemplate.query(CANDIDATES_SQL, (rs, i) -> rs.getObject(1, UUID.class));
        } catch (DataAccessException ex) {
            log.error("Legacy allergy backfill could not list candidates: {}", ex.getMessage());
            return null;
        }
        Map<LegacyAllergyTextBackfillWorker.Outcome, Integer> outcomes =
            new EnumMap<>(LegacyAllergyTextBackfillWorker.Outcome.class);
        if (candidates.isEmpty()) {
            return new Summary(0, outcomes, 0);
        }
        int failed = 0;
        for (UUID patientId : candidates) {
            try {
                outcomes.merge(worker.backfillOne(patientId), 1, Integer::sum);
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Legacy allergy backfill failed for patient {}: {}", patientId, ex.getMessage());
            }
        }
        Summary summary = new Summary(candidates.size(), outcomes, failed);
        log.info("Legacy allergy backfill: {} candidate(s), {} imported, {} nothing new, {} already done, "
                + "{} nothing to import, {} without a hospital, {} failed",
            summary.candidates(),
            summary.count(LegacyAllergyTextBackfillWorker.Outcome.IMPORTED),
            summary.count(LegacyAllergyTextBackfillWorker.Outcome.NOTHING_NEW),
            summary.count(LegacyAllergyTextBackfillWorker.Outcome.ALREADY_DONE),
            summary.count(LegacyAllergyTextBackfillWorker.Outcome.NOTHING_TO_IMPORT),
            summary.count(LegacyAllergyTextBackfillWorker.Outcome.NO_HOSPITAL),
            summary.failed());
        return summary;
    }
}
