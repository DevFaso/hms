package com.example.hms.service.allergy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegacyAllergyTextBackfillTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private LegacyAllergyTextBackfillWorker worker;

    @InjectMocks
    private LegacyAllergyTextBackfill backfill;

    @Test
    @DisplayName("every candidate runs on its own; one failure does not stop the others")
    void runsEveryCandidateAndSurvivesAFailure() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(jdbcTemplate.query(eq(LegacyAllergyTextBackfill.CANDIDATES_SQL), any(RowMapper.class)))
            .thenReturn(List.of(a, b, c));
        when(worker.backfillOne(a)).thenReturn(LegacyAllergyTextBackfillWorker.Outcome.IMPORTED);
        when(worker.backfillOne(b)).thenThrow(new IllegalStateException("boom"));
        when(worker.backfillOne(c)).thenReturn(LegacyAllergyTextBackfillWorker.Outcome.ALREADY_DONE);

        LegacyAllergyTextBackfill.Summary summary = backfill.pass();

        verify(worker).backfillOne(a);
        verify(worker).backfillOne(b);
        verify(worker).backfillOne(c);
        assertThat(summary.candidates()).isEqualTo(3);
        assertThat(summary.count(LegacyAllergyTextBackfillWorker.Outcome.IMPORTED)).isEqualTo(1);
        assertThat(summary.count(LegacyAllergyTextBackfillWorker.Outcome.ALREADY_DONE)).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("D2220: a patient whose text created no row is counted as nothing new, never as imported")
    void nothingNewIsNotImported() {
        UUID a = UUID.randomUUID();
        when(jdbcTemplate.query(eq(LegacyAllergyTextBackfill.CANDIDATES_SQL), any(RowMapper.class)))
            .thenReturn(List.of(a));
        when(worker.backfillOne(a)).thenReturn(LegacyAllergyTextBackfillWorker.Outcome.NOTHING_NEW);

        LegacyAllergyTextBackfill.Summary summary = backfill.pass();

        assertThat(summary.count(LegacyAllergyTextBackfillWorker.Outcome.IMPORTED)).isZero();
        assertThat(summary.count(LegacyAllergyTextBackfillWorker.Outcome.NOTHING_NEW)).isEqualTo(1);
    }

    @Test
    @DisplayName("D2220: the candidate query excludes every patient the ledger has recorded")
    void candidatesExcludeTheLedger() {
        assertThat(LegacyAllergyTextBackfill.CANDIDATES_SQL)
            .contains("NOT EXISTS (SELECT 1 FROM clinical.patient_allergy_legacy_imports l WHERE l.patient_id = p.id)");
    }

    @Test
    @DisplayName("a database error listing candidates is logged and startup continues")
    void surviveCandidateQueryFailure() {
        when(jdbcTemplate.query(eq(LegacyAllergyTextBackfill.CANDIDATES_SQL), any(RowMapper.class)))
            .thenThrow(new DataAccessResourceFailureException("db down"));

        backfill.run();

        verify(worker, never()).backfillOne(any());
    }

    @Test
    @DisplayName("no candidates, no work")
    void noCandidates() {
        when(jdbcTemplate.query(eq(LegacyAllergyTextBackfill.CANDIDATES_SQL), any(RowMapper.class)))
            .thenReturn(List.of());

        backfill.run();

        verify(worker, never()).backfillOne(any());
    }
}
