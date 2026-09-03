package com.example.hms.security.audit;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Deliberately not a @Component. It is constructed by
 * PatientAccessAuditInterceptor, which owns it — see the field comment there.
 */

/**
 * Collapses a clinician's repeated reads of one patient into a single recorded
 * access.
 *
 * <p><b>Why this exists.</b> Opening a patient's chart is not one request. It
 * is allergies, diagnoses, vitals, labs, imaging, notes — a dozen or more GETs
 * that a person experiences as one act of looking at a record. Recorded
 * literally, a single chart open would put a dozen lines on the patient's
 * disclosure page, and a day of ordinary care would bury the one break-glass
 * access that actually warranted their attention. That failure is not milder
 * than the under-reporting this was built to fix; it is the same failure —
 * a page that cannot be read for the answer it exists to give.
 *
 * <p><b>Which way it errs.</b> The state is per-instance and in memory, so a
 * restart or a second instance can let the same access through twice. That is
 * deliberate. Every failure mode here over-records rather than under-records:
 * a duplicate line is a cosmetic flaw on a page the patient can still read,
 * whereas a missed line is the page asserting that nobody looked. Durable
 * cross-instance state would trade that safety for a write on every read, and
 * the disclosure page is not worth taxing every chart open to make exact.
 *
 * <p><b>Memory.</b> Bounded by {@code maxEntries}. When the map exceeds it,
 * expired keys are swept first; if that is not enough the map is cleared
 * outright, which re-opens the window for everyone currently active. Again
 * the safe direction — clearing causes extra rows, never missing ones.
 */
public class PatientAccessDedupe {

    private final Map<String, Long> lastRecorded = new ConcurrentHashMap<>();

    private final long windowMillis;
    private final int maxEntries;

    public PatientAccessDedupe(long windowMinutes, int maxEntries) {
        this.windowMillis = Duration.ofMinutes(windowMinutes).toMillis();
        this.maxEntries = maxEntries;
    }

    /**
     * Whether this access should be written, given what has already been
     * written for the same clinician and patient.
     *
     * <p>Atomic per key: two concurrent requests for the same pair produce one
     * {@code true}, not two, even when they carry the same timestamp.
     *
     * @param actorUserId the clinician reading
     * @param patientId   the patient being read
     * @param nowMillis   current time, passed in so tests need no clock
     * @return true exactly once per window
     */
    public boolean shouldRecord(UUID actorUserId, UUID patientId, long nowMillis) {
        if (actorUserId == null || patientId == null) {
            return false;
        }
        String key = actorUserId + ":" + patientId;
        long cutoff = nowMillis - windowMillis;

        // compute() holds the bin lock for this key, so the read of the
        // previous stamp and the write of the new one cannot interleave with
        // another request for the same pair. A plain get-then-put here would
        // let a burst of parallel calls — which is exactly what one chart open
        // is — each see "nothing recorded yet" and all emit.
        boolean[] record = {false};
        lastRecorded.compute(key, (k, previous) -> {
            if (previous == null || previous <= cutoff) {
                record[0] = true;
                return nowMillis;
            }
            return previous;
        });

        if (lastRecorded.size() > maxEntries) {
            evict(cutoff);
        }
        return record[0];
    }

    private void evict(long cutoff) {
        Iterator<Map.Entry<String, Long>> it = lastRecorded.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= cutoff) {
                it.remove();
            }
        }
        if (lastRecorded.size() > maxEntries) {
            // Everything tracked is still inside its window, so nothing can be
            // dropped without re-opening it. Clearing does exactly that, for
            // everyone — the bounded-memory guarantee is worth more than the
            // duplicate rows it costs, and duplicates are the safe direction.
            lastRecorded.clear();
        }
    }

    /** Visible for tests: how many pairs are currently being tracked. */
    int tracked() {
        return lastRecorded.size();
    }
}
