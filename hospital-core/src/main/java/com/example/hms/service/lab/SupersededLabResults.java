package com.example.hms.service.lab;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which stored lab results a later one has left behind.
 *
 * <p>An analyzer commonly reports one observation twice — preliminary, then
 * final — in two HL7 messages with two different MSH-10s. Both are stored, and
 * they have to be: the message control id is the only key the replay guard and
 * the partial unique index have, the set id keeps a timed series distinct, and
 * a critical value is notified against the row it was raised on. Collapsing
 * them at ingest costs records; a record is the thing we cannot reconstruct.
 *
 * <p>So the pair is resolved where being wrong costs a rendering instead: the
 * patient is shown one row per observation, and order completion stops waiting
 * on a row the laboratory has already moved past. Both read paths ask this
 * class the same question, so they cannot drift into disagreeing — a patient
 * seeing a finished result while the order it belongs to never completes is
 * exactly the split this prevents.
 *
 * <h2>What counts as the same observation</h2>
 *
 * <p>The same analyte, in the same position, on the same order:
 * {@code (labOrderId, testCode, observationSetId)}. The set id is load-bearing
 * and leaving it out was a patient-safety bug — without it a timed series of
 * one analyte (glucose at 0, 30 and 60 minutes, which is precisely what the
 * set id exists to keep distinct) read as one observation reported repeatedly,
 * so an abnormal later draw was hidden behind the normal first one that had
 * been auto-released. A row missing any part of the key names no observation
 * and stands alone: a hand-entered result is nobody's preliminary.
 *
 * <h2>Which row of a group survives</h2>
 *
 * <p>The latest, and only ever the latest — by observation time, then by the
 * order the rows were written, then preferring the released one. Recency
 * decides rather than release, for two reasons. An earlier row must never
 * supersede a later one, or a correction issued after a release would vanish
 * behind the value it corrects. And an abnormal final following a normal
 * preliminary is often NOT auto-releasable, so if release decided the pairing
 * nothing would be superseded and the patient would see the same test pending
 * twice.
 *
 * <p>The consequence worth stating: when a correction arrives and has not been
 * released yet, the patient stops seeing the released value it supersedes and
 * sees the pending correction instead. That is the honest reading — the
 * laboratory is revising that result — and it is the safer of the two, because
 * the alternative is showing a value that has been withdrawn.
 */
public final class SupersededLabResults {

    private SupersededLabResults() {
    }

    /** One observation: the analyte, in its position, on its order. */
    public record AnalyteKey(UUID labOrderId, String testCode, String observationSetId) {
    }

    /**
     * The rows that a later row for the same observation has left behind.
     *
     * <p>Identity-based, so it answers for exactly the row objects passed in
     * and needs no persisted id. Rows that name no observation are never
     * included. The argument is required; the rows are a repository result and
     * its elements are entities.
     */
    public static Set<LabResult> superseded(Collection<LabResult> rows) {
        Map<AnalyteKey, LabResult> latestPerObservation = new HashMap<>();
        Set<LabResult> superseded = Collections.newSetFromMap(new IdentityHashMap<>());
        for (LabResult row : rows) {
            AnalyteKey key = analyteKey(row);
            if (key == null) {
                continue;
            }
            LabResult incumbent = latestPerObservation.get(key);
            if (incumbent == null) {
                latestPerObservation.put(key, row);
            } else if (supersedes(row, incumbent)) {
                superseded.add(incumbent);
                latestPerObservation.put(key, row);
            } else {
                superseded.add(row);
            }
        }
        return superseded;
    }

    /**
     * Whether this row replaces the one we are holding: later observation
     * time, or the same time and written afterwards, or — the analyzer having
     * given us nothing to separate them — the released one over the pending.
     */
    private static boolean supersedes(LabResult candidate, LabResult incumbent) {
        int byObservationTime = compare(candidate.getResultDate(), incumbent.getResultDate());
        if (byObservationTime != 0) {
            return byObservationTime > 0;
        }
        int byWriteOrder = compare(candidate.getCreatedAt(), incumbent.getCreatedAt());
        if (byWriteOrder != 0) {
            return byWriteOrder > 0;
        }
        return candidate.isReleased() && !incumbent.isReleased();
    }

    /** An absent timestamp sorts earliest, so a row that carries one always wins. */
    private static int compare(LocalDateTime left, LocalDateTime right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return left.compareTo(right);
    }

    private static AnalyteKey analyteKey(LabResult row) {
        LabOrder order = row.getLabOrder();
        String testCode = row.getTestCode();
        String setId = row.getSourceObservationSetId();
        if (order == null || order.getId() == null
            || testCode == null || testCode.isBlank()
            || setId == null || setId.isBlank()) {
            return null;
        }
        return new AnalyteKey(order.getId(), testCode, setId);
    }
}
