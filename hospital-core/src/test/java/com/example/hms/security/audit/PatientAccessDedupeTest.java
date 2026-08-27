package com.example.hms.security.audit;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PatientAccessDedupe")
class PatientAccessDedupeTest {

    private static final long MINUTE = 60_000L;

    private PatientAccessDedupe dedupe(long windowMinutes, int maxEntries) {
        return new PatientAccessDedupe(windowMinutes, maxEntries);
    }

    @Test
    @DisplayName("one chart open is one recorded access, not one per request")
    void collapsesTheRequestsOfASingleChartOpen() {
        // The reason this class exists. Opening a chart fires a dozen GETs —
        // allergies, diagnoses, vitals, labs — and a patient reading their
        // disclosure page must see that as one person looking once.
        PatientAccessDedupe d = dedupe(30, 1000);
        UUID doctor = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        long t = 1_000_000L;

        long recorded = IntStream.range(0, 12)
            .filter(i -> d.shouldRecord(doctor, patient, t + i * 40L))
            .count();

        assertThat(recorded).isEqualTo(1);
    }

    @Test
    @DisplayName("a later visit is a new access")
    void recordsAgainOnceTheWindowHasPassed() {
        PatientAccessDedupe d = dedupe(30, 1000);
        UUID doctor = UUID.randomUUID();
        UUID patient = UUID.randomUUID();

        assertThat(d.shouldRecord(doctor, patient, 0L)).isTrue();
        assertThat(d.shouldRecord(doctor, patient, 29 * MINUTE)).isFalse();
        assertThat(d.shouldRecord(doctor, patient, 31 * MINUTE)).isTrue();
    }

    @Test
    @DisplayName("collapsing is per clinician and per patient, never across either")
    void doesNotCollapseAcrossDifferentPeople() {
        // The dangerous bug in a dedupe key is over-matching: if two
        // clinicians collapsed together, the second one's access would vanish
        // from the patient's page entirely, which is the under-reporting this
        // whole change exists to end.
        PatientAccessDedupe d = dedupe(30, 1000);
        UUID doctorA = UUID.randomUUID();
        UUID doctorB = UUID.randomUUID();
        UUID patient1 = UUID.randomUUID();
        UUID patient2 = UUID.randomUUID();

        assertThat(d.shouldRecord(doctorA, patient1, 0L)).isTrue();
        assertThat(d.shouldRecord(doctorB, patient1, 0L)).as("second clinician").isTrue();
        assertThat(d.shouldRecord(doctorA, patient2, 0L)).as("second patient").isTrue();
    }

    @Test
    @DisplayName("a burst of parallel requests still records exactly once")
    void isAtomicUnderConcurrency() throws Exception {
        // A chart open fires its requests in parallel, so this is the real
        // shape of the load, not a contrived race. get-then-put here would let
        // every one of them see "nothing recorded yet".
        PatientAccessDedupe d = dedupe(30, 1000);
        UUID doctor = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        int threads = 16;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> calls = IntStream.range(0, threads)
                .<Callable<Boolean>>mapToObj(i -> () -> d.shouldRecord(doctor, patient, 5_000L))
                .toList();

            long recorded = 0;
            for (Future<Boolean> result : pool.invokeAll(calls)) {
                if (Boolean.TRUE.equals(result.get())) {
                    recorded++;
                }
            }
            assertThat(recorded).isEqualTo(1);
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("memory stays bounded, and over-records rather than under-records to stay that way")
    void staysBounded() {
        // Every entry below is inside its window, so nothing can be evicted
        // without re-opening it. The class chooses the bound and accepts the
        // duplicate rows — a duplicate line is cosmetic, a missing one is the
        // page telling a patient nobody looked.
        PatientAccessDedupe d = dedupe(30, 100);
        UUID patient = UUID.randomUUID();

        for (int i = 0; i < 500; i++) {
            d.shouldRecord(UUID.randomUUID(), patient, 1_000L);
        }

        assertThat(d.tracked()).isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("expired entries are swept before anything live is dropped")
    void sweepsExpiredBeforeClearing() {
        PatientAccessDedupe d = dedupe(30, 10);
        UUID patient = UUID.randomUUID();
        UUID stillActive = UUID.randomUUID();

        for (int i = 0; i < 10; i++) {
            d.shouldRecord(UUID.randomUUID(), patient, 0L);
        }
        d.shouldRecord(stillActive, patient, 60 * MINUTE);

        // The old ten are outside the window at this point, so the sweep
        // reclaims them and the live entry survives — meaning the clinician
        // who just looked is still deduped rather than recorded twice.
        assertThat(d.shouldRecord(stillActive, patient, 60 * MINUTE + 1_000L)).isFalse();
    }

    @Test
    @DisplayName("a missing actor or patient records nothing rather than keying on null")
    void ignoresNulls() {
        PatientAccessDedupe d = dedupe(30, 1000);
        assertThat(d.shouldRecord(null, UUID.randomUUID(), 0L)).isFalse();
        assertThat(d.shouldRecord(UUID.randomUUID(), null, 0L)).isFalse();
    }
}
