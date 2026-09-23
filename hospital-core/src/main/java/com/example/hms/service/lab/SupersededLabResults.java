package com.example.hms.service.lab;

import com.example.hms.enums.ActorType;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 *   <li>Another row exists for the same order, test code, sender AND
 *       observation time — the analyzer's own identification of the
 *       observation, not our positional numbering. "Sender" is the sending
 *       application, or the sending facility when the application is absent,
 *       matching the guard that decides whether a result came from an
 *       analyzer at all. The observation time is part of it because a
 *       preliminary and its final describe ONE draw and carry ONE OBX-14,
 *       whereas a timed series is several draws of the same analyte at
 *       different times; without it the series collapsed as soon as any draw
 *       carried a preliminary status, which is the patient-safety case this
 *       class exists for.</li>
 *   <li>That row is strictly newer — the pair share an observation time by
 *       construction, so this is write order, and finally row id, and the
 *       outcome is decided by the data rather than by the order a query
 *       returned.</li>
 *   <li>It does not hide a released row behind an unreleased one. A value the
 *       patient has already been given is never taken away and replaced with
 *       nothing.</li>
 * </ol>
 *
 * <h2>And for an ANALYZER row written before V164</h2>
 *
 * <p>V164 backfills nothing, so every analyzer row already in the database has
 * no status. Judging those by the rule above would supersede none of them —
 * and their preliminary/final pairs are real: the order would sit in RESULTED
 * for ever and the patient would keep a duplicate pending row, which is worse
 * than the behaviour they have today. So such a row is judged by the older
 * rule instead, unchanged from what shipped in #720: the same order and test
 * code, with a LATER RELEASED row winning.
 *
 * <p><strong>Which rows those are is decided by provenance, not by the absence
 * of a status.</strong> "No status" is not "old data" — it is also every
 * hand-entered and REST-written result, for ever, and selecting the fallback
 * that way handed those rows a rule they were never meant to have: a released
 * row could hide an unreleased correction and close the order over it, exactly
 * the behaviour this class was changed to stop. The fallback therefore applies
 * only to a row that CAME FROM AN ANALYZER ({@link #cameFromAnAnalyzer}) and
 * carries no status. A result a person entered is judged by neither rule and
 * is never hidden.
 *
 * <p>Two rules, chosen by what the row is and what the analyzer said, and
 * nothing reconstructed. New analyzer data gets the exact behaviour; older
 * analyzer data keeps the behaviour it already has; a human's result is left
 * alone. Because nothing new lands in the fallback, it genuinely retires as
 * pre-V164 rows age out of what anyone reads.
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

    /** One observation, as the analyzer identifies it: one analyte, one sender, one draw. */
    private record ObservationKey(UUID labOrderId, String testCode, String sender,
                                  LocalDateTime observedAt) {
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
        return replacements(rowsToJudge, knownRows).keySet();
    }

    /**
     * Each superseded row's id, mapped to the row that replaced it.
     *
     * <p>The replacement matters as much as the removal: a caller rendering a
     * page must be able to put the surviving row in front of the reader, or
     * removing a superseded row at the page edge would make the test disappear
     * from the patient's view altogether.
     */
    public static Map<UUID, LabResult> replacements(Collection<LabResult> rowsToJudge,
                                                    Collection<LabResult> knownRows) {
        Map<UUID, LabResult> replacedBy = new LinkedHashMap<>();
        for (LabResult row : rowsToJudge) {
            replacementFor(row, knownRows).ifPresent(winner -> replacedBy.put(row.getId(), winner));
        }
        return replacedBy;
    }

    /** The row that replaces this one, under whichever rule applies to it. */
    private static Optional<LabResult> replacementFor(LabResult row, Collection<LabResult> knownRows) {
        if (row.getId() == null) {
            return Optional.empty();
        }
        if (hasAnalyzerStatus(row)) {
            return replacementUnderTheAnalyzerRule(row, knownRows);
        }
        return cameFromAnAnalyzer(row)
            ? replacementUnderTheRuleForAnalyzerRowsWrittenBeforeV164(row, knownRows)
            : Optional.empty();
    }

    /** The analyzer described this row, so the precise rule applies. */
    private static Optional<LabResult> replacementUnderTheAnalyzerRule(
        LabResult row, Collection<LabResult> knownRows) {
        if (!isAnalyzerPreliminary(row)) {
            return Optional.empty();
        }
        ObservationKey key = observationKey(row);
        return key == null
            ? Optional.empty()
            : knownRows.stream().filter(candidate -> replaces(candidate, row, key)).findFirst();
    }

    /**
     * An analyzer row with no status: it predates V164, or came from an
     * analyzer that sent no OBX-11. Judged by what shipped in #720 — an
     * unreleased row for the same order and test code, replaced by a LATER
     * RELEASED one.
     *
     * <p>Deliberately not tightened to the sender or the observation time:
     * this is the behaviour these rows already have in production, and the
     * point of keeping it is that they keep working exactly as they do now.
     * It is reached only for rows that came from an analyzer, so nothing new
     * lands here.
     */
    private static Optional<LabResult> replacementUnderTheRuleForAnalyzerRowsWrittenBeforeV164(
        LabResult row, Collection<LabResult> knownRows) {
        if (row.isReleased()) {
            return Optional.empty();
        }
        AnalyteKey key = analyteKey(row);
        if (key == null) {
            return Optional.empty();
        }
        return knownRows.stream()
            .filter(candidate -> candidate != null
                && candidate.getId() != null
                && !candidate.getId().equals(row.getId())
                && candidate.isReleased()
                && key.equals(analyteKey(candidate))
                && isStrictlyNewer(candidate, row))
            .findFirst();
    }

    /**
     * Whether this row reached us from an analyzer rather than from a person.
     *
     * <p>Every mark the MLLP ingest leaves: the actor type it always sets, and
     * the three source columns, each of which a given sender may omit. Shared
     * with the transmit guard, which asks the same question — whether we sent
     * this result or received it — so the two cannot drift apart.
     */
    public static boolean cameFromAnAnalyzer(LabResult row) {
        return row.getActorType() == ActorType.SYSTEM
            || row.getSourceMessageControlId() != null
            || row.getSourceSendingApplication() != null
            || row.getSourceSendingFacility() != null;
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
     * A row that may be hidden: an analyzer row the analyzer called
     * preliminary, or an analyzer row with no status that is unreleased. Used
     * by the read paths to decide which orders they need the siblings of, so
     * it must be as narrow as the rules themselves — a hand-entered pending
     * result is not a candidate and must not provoke the sibling query.
     */
    public static boolean mayBeSuperseded(LabResult row) {
        if (!cameFromAnAnalyzer(row)) {
            return false;
        }
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
        LocalDateTime observedAt = row.getResultDate();
        return analyte == null || sender == null || observedAt == null
            ? null
            : new ObservationKey(analyte.labOrderId(), analyte.testCode(), sender, observedAt);
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
