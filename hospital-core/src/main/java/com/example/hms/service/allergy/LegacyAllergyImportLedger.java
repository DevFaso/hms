package com.example.hms.service.allergy;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * QA D2220: the durable record of which patients the legacy free-text
 * allergy backfill has already processed ({@code clinical.patient_allergy_legacy_imports}, V315).
 *
 * <p>Done-ness used to be inferred from {@code patient_allergies} (a row with
 * a free-text source), so a patient whose text matched rows recorded by hand
 * was re-processed on every start, and a clinician's deactivation could be
 * undone by the next deploy. A patient is now marked once, in the worker's
 * own transaction, whatever the import created, and never processed again.
 *
 * <p>Every statement is a full constant literal, values bound as parameters.
 * No PHI is written: a patient id, a fixed outcome code and a count.
 */
@Component
@RequiredArgsConstructor
public class LegacyAllergyImportLedger {

    static final String IS_DONE_SQL =
        "SELECT COUNT(*) FROM clinical.patient_allergy_legacy_imports WHERE patient_id = ?";

    static final String MARK_SQL =
        "INSERT INTO clinical.patient_allergy_legacy_imports (patient_id, outcome, rows_created)"
            + " SELECT CAST(? AS UUID), ?, ? WHERE NOT EXISTS"
            + " (SELECT 1 FROM clinical.patient_allergy_legacy_imports WHERE patient_id = CAST(? AS UUID))";

    private final JdbcTemplate jdbcTemplate;

    /** Whether the backfill has already processed this patient. */
    public boolean isDone(UUID patientId) {
        Integer count = jdbcTemplate.queryForObject(IS_DONE_SQL, Integer.class, patientId);
        return count != null && count > 0;
    }

    /**
     * Record that the backfill processed this patient. A second mark is
     * ignored: the first outcome stands. (Portable SQL, not ON CONFLICT, which
     * the H2 test database lacks; a true race between two instances hits the
     * primary key, that patient's transaction rolls back, and the winner's
     * row stands.)
     */
    public void markDone(UUID patientId, LegacyAllergyTextBackfillWorker.Outcome outcome, int rowsCreated) {
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(outcome, "outcome");
        jdbcTemplate.update(MARK_SQL, patientId, outcome.name(), rowsCreated, patientId);
    }
}
