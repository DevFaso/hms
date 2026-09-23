package com.example.hms.service.lab;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Which stored lab results the analyzer has told us it replaced.
 *
 * <p>An analyzer commonly reports one observation twice — preliminary, then
 * final — in two HL7 messages with two different MSH-10s. Both rows are kept,
 * and have to be: the message control id is the only key the replay guard and
 * the partial unique index have, and a critical value is notified against the
 * row it was raised on. Collapsing them at ingest costs records, and a record
 * is the thing we cannot reconstruct. So the pair is resolved on the way out,
 * where being wrong costs a rendering: the patient sees one row per
 * observation, and order completion does not wait on a value the laboratory
 * has already replaced.
 *
 * <h2>Why this asks the analyzer instead of guessing</h2>
 *
 * <p>Deciding which row to hide was attempted three times by inference, and
 * each inference had a hole:
 *
 * <ul>
 *   <li><strong>The observation set id.</strong> HL7 guarantees it only
 *       WITHIN a message, and our ingest falls back to positional numbering,
 *       so a final that re-sends a subset of the preliminary's panel carries a
 *       different set id for the same analyte — nothing superseded anything
 *       and the patient kept a permanent "pending". One message per draw gives
 *       every draw set id 1, so a timed series collided on a single key and an
 *       abnormal earlier draw could be hidden by a later normal one.</li>
 *   <li><strong>Release state.</strong> It cannot tell a preliminary from a
 *       correction, so a corrected result could hide the released value it
 *       corrects, or be hidden by it.</li>
 *   <li><strong>Recency alone.</strong> Same problem from the other side, and
 *       with both timestamps equal — the ordinary case, since a preliminary
 *       and its final carry the same observation time — the winner fell to
 *       whatever order the repository happened to return.</li>
 * </ul>
 *
 * <p>OBX-11 says outright what a result is, and since V164 we store it. A row
 * is a preliminary only if the analyzer said {@code P}. Everything else is
 * shown, which is what this system did before any of this existed and is the
 * behaviour that cannot lose data.
 *
 * <h2>The rule</h2>
 *
 * <p>A row is superseded when ALL of the following hold. Anything short of
 * them shows both rows.
 *
 * <ol>
 *   <li>The analyzer marked it preliminary ({@code OBX-11 = P}).</li>
 *   <li>Another row exists for the same order, the same test code and the
 *       same sending application — the analyzer's own identification of the
 *       observation, not our positional numbering.</li>
 *   <li>That row is strictly newer, by observation time, then by write order,
 *       and finally by row id, so the outcome is decided by the data and never
 *       by the order a query returned.</li>
 *   <li>It does not hide a released row behind an unreleased one. A value the
 *       patient has already been given is never taken away and replaced with
 *       nothing.</li>
 * </ol>
 *
 * <p>Point 4 is a deliberate reversal of an earlier reading of this class,
 * which hid a released value as soon as an unreleased correction arrived.
 * Leaving the patient a bare "pending" where a number used to be is worse than
 * briefly showing the value the laboratory has not yet withdrawn. The better
 * answer is to show both — the released value AND "corrected result pending" —
 * and that belongs here as soon as the portal can express it; until then the
 * released value stands.
 */
public final class SupersededLabResults {

    /** HL7 table 0085: the analyzer says this observation is not finished. */
    private static final String PRELIMINARY = "P";

    private SupersededLabResults() {
    }

    /** One observation, as the analyzer identifies it. */
    private record ObservationKey(UUID labOrderId, String testCode, String sendingApplication) {
    }

    /**
     * The ids of the rows in {@code rowsToJudge} that a later row in
     * {@code knownRows} has replaced.
     *
     * <p>{@code knownRows} is every row that could do the replacing — for a
     * page of results, the page plus the siblings of the analytes on it; for
     * order completion, every result on the order. Returning ids rather than
     * instances keeps the answer usable across two queries, which may hand
     * back different objects for the same row.
     */
    public static Set<UUID> supersededRowIds(Collection<LabResult> rowsToJudge,
                                             Collection<LabResult> knownRows) {
        Set<UUID> superseded = new HashSet<>();
        for (LabResult row : rowsToJudge) {
            if (hasBeenReplaced(row, knownRows)) {
                superseded.add(row.getId());
            }
        }
        return superseded;
    }

    /** Whether some known row replaces this one, under every condition of the rule above. */
    private static boolean hasBeenReplaced(LabResult row, Collection<LabResult> knownRows) {
        if (row.getId() == null || !isAnalyzerPreliminary(row)) {
            return false;
        }
        ObservationKey key = observationKey(row);
        return key != null && knownRows.stream().anyMatch(candidate -> replaces(candidate, row, key));
    }

    /** Whether the analyzer marked this row preliminary. Nothing else writes the status. */
    public static boolean isAnalyzerPreliminary(LabResult row) {
        String status = row.getObservationResultStatus();
        return status != null && PRELIMINARY.equals(status.trim().toUpperCase(Locale.ROOT));
    }

    private static boolean replaces(LabResult candidate, LabResult preliminary, ObservationKey key) {
        if (candidate == null || candidate.getId() == null
            || candidate.getId().equals(preliminary.getId())
            || !key.equals(observationKey(candidate))) {
            return false;
        }
        // Never take away a value the patient already has and leave nothing.
        if (preliminary.isReleased() && !candidate.isReleased()) {
            return false;
        }
        return isStrictlyNewer(candidate, preliminary);
    }

    /**
     * Observation time first, then the order the rows were written, then the
     * row id. The id carries no meaning; it is there so that two rows the
     * analyzer stamped identically still resolve the same way on every read,
     * rather than following whatever order a query returned.
     */
    private static boolean isStrictlyNewer(LabResult candidate, LabResult incumbent) {
        int byObservationTime = compare(candidate.getResultDate(), incumbent.getResultDate());
        if (byObservationTime != 0) {
            return byObservationTime > 0;
        }
        int byWriteOrder = compare(candidate.getCreatedAt(), incumbent.getCreatedAt());
        if (byWriteOrder != 0) {
            return byWriteOrder > 0;
        }
        return candidate.getId().compareTo(incumbent.getId()) > 0;
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

    private static ObservationKey observationKey(LabResult row) {
        LabOrder order = row.getLabOrder();
        String testCode = row.getTestCode();
        String sendingApplication = row.getSourceSendingApplication();
        if (order == null || order.getId() == null
            || testCode == null || testCode.isBlank()
            || sendingApplication == null || sendingApplication.isBlank()) {
            return null;
        }
        return new ObservationKey(order.getId(), testCode, sendingApplication);
    }
}
