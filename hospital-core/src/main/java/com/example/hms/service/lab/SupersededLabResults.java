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
 * <h2>The rule, for a row the analyzer described</h2>
 *
 * <p>A row is superseded when ALL of the following hold. Anything short of
 * them shows both rows.
 *
 * <ol>
 *   <li>The analyzer marked it preliminary ({@code OBX-11 = P}).</li>
 *   <li>Another row exists for the same order, the same test code and the
 *       same sender — the analyzer's own identification of the observation,
 *       not our positional numbering. "Sender" is the sending application,
 *       or the sending facility when the application is absent, matching the
 *       guard that decides whether a result came from an analyzer at all.</li>
 *   <li>That row is strictly newer, by observation time, then by write order,
 *       and finally by row id, so the outcome is decided by the data and never
 *       by the order a query returned.</li>
 *   <li>It does not hide a released row behind an unreleased one. A value the
 *       patient has already been given is never taken away and replaced with
 *       nothing.</li>
 * </ol>
 *
 * <h2>And for a row written before V164</h2>
 *
 * <p>V164 backfills nothing, so every analyzer row already in the database has
 * no status. Judging those by the rule above would supersede none of them —
 * and their preliminary/final pairs are real: the order would sit in RESULTED
 * for ever and the patient would keep a duplicate pending row, which is worse
 * than the behaviour they have today. So a row whose status is absent is
 * judged by the older rule instead, unchanged from what shipped in #720: the
 * same order and test code, with a LATER RELEASED row winning.
 *
 * <p>Two rules, chosen by whether the analyzer told us, and nothing
 * reconstructed. New data gets the exact behaviour; old data keeps the
 * behaviour it already has; no row is guessed at. The fallback retires by
 * itself as pre-V164 rows age out of what anyone reads.
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
    private record ObservationKey(UUID labOrderId, String testCode, String sender) {
    }

    /** One analyte on one order — the older rule's key, for rows written before V164. */
    private record AnalyteKey(UUID labOrderId, String testCode) {
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

    /** Whether some known row replaces this one, under whichever rule applies to it. */
    private static boolean hasBeenReplaced(LabResult row, Collection<LabResult> knownRows) {
        if (row.getId() == null) {
            return false;
        }
        return hasAnalyzerStatus(row)
            ? replacedUnderTheAnalyzerRule(row, knownRows)
            : replacedUnderTheRuleForRowsWrittenBeforeV164(row, knownRows);
    }

    /** The analyzer described this row, so the precise rule applies. */
    private static boolean replacedUnderTheAnalyzerRule(LabResult row, Collection<LabResult> knownRows) {
        if (!isAnalyzerPreliminary(row)) {
            return false;
        }
        ObservationKey key = observationKey(row);
        return key != null && knownRows.stream().anyMatch(candidate -> replaces(candidate, row, key));
    }

    /**
     * No status, so the row predates V164 (or came from an analyzer that sent
     * no OBX-11). Judged by what shipped in #720: an unreleased row for the
     * same order and test code, replaced by a LATER RELEASED one.
     *
     * <p>Deliberately not tightened to the sender: this is the behaviour these
     * rows already have in production, and the point of keeping it is that they
     * keep working exactly as they do now.
     */
    private static boolean replacedUnderTheRuleForRowsWrittenBeforeV164(
        LabResult row, Collection<LabResult> knownRows) {
        if (row.isReleased()) {
            return false;
        }
        AnalyteKey key = analyteKey(row);
        return key != null && knownRows.stream().anyMatch(candidate ->
            candidate != null
                && candidate.getId() != null
                && !candidate.getId().equals(row.getId())
                && candidate.isReleased()
                && key.equals(analyteKey(candidate))
                && isStrictlyNewer(candidate, row));
    }

    /** Whether the analyzer told us what this row is at all. */
    public static boolean hasAnalyzerStatus(LabResult row) {
        String status = row.getObservationResultStatus();
        return status != null && !status.isBlank();
    }

    /** Whether the analyzer marked this row preliminary. Nothing else writes the status. */
    public static boolean isAnalyzerPreliminary(LabResult row) {
        String status = row.getObservationResultStatus();
        return status != null && PRELIMINARY.equals(status.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * A row that may be hidden: the analyzer called it preliminary, or it
     * predates V164 and is unreleased. Used by the read paths to decide which
     * orders they need the siblings of.
     */
    public static boolean mayBeSuperseded(LabResult row) {
        return hasAnalyzerStatus(row) ? isAnalyzerPreliminary(row) : !row.isReleased();
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
        AnalyteKey analyte = analyteKey(row);
        String sender = senderOf(row);
        return analyte == null || sender == null
            ? null
            : new ObservationKey(analyte.labOrderId(), analyte.testCode(), sender);
    }

    private static AnalyteKey analyteKey(LabResult row) {
        LabOrder order = row.getLabOrder();
        String testCode = row.getTestCode();
        if (order == null || order.getId() == null || testCode == null || testCode.isBlank()) {
            return null;
        }
        return new AnalyteKey(order.getId(), testCode);
    }

    /**
     * Who sent this row: the sending application, or the sending facility when
     * the application is absent.
     *
     * <p>The ingest stores both trimmed-to-null, and a sender may identify
     * itself by facility alone — which is exactly why the transmit guard was
     * widened to check all three source columns. Demanding the application
     * here would have left such a sender's pairs never collapsing and its
     * orders never completing.
     */
    private static String senderOf(LabResult row) {
        String application = row.getSourceSendingApplication();
        if (application != null && !application.isBlank()) {
            return application;
        }
        String facility = row.getSourceSendingFacility();
        return facility != null && !facility.isBlank() ? facility : null;
    }
}
