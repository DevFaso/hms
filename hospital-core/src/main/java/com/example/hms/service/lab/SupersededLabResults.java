package com.example.hms.service.lab;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Which stored lab results a finished one has left behind.
 *
 * <p>An analyzer commonly reports one observation twice — preliminary, then
 * final — in two HL7 messages with two different MSH-10s. Both are stored, and
 * they have to be: the message control id is the only key the replay guard and
 * the partial unique index have, the set id keeps a timed series distinct, and
 * a critical value is notified against the row it was raised on. Collapsing
 * them at ingest costs records; a record is the thing we cannot reconstruct.
 *
 * <p>So the pair is resolved where being wrong costs a rendering instead: the
 * patient is shown the finished value alone rather than a "pending" beside it,
 * and order completion stops waiting on a preliminary the lab has already
 * moved past. Both read paths ask this class the same question, so they cannot
 * drift into disagreeing — a patient seeing a finished result while the order
 * it belongs to never completes is exactly the split this prevents.
 *
 * <p>"The same observation" means the same analyte on the same order. A row
 * with no test code names no analyte and is never treated as superseded: a
 * hand-entered result is nobody's preliminary.
 */
public final class SupersededLabResults {

    private SupersededLabResults() {
    }

    /** The analyte of one row: its order and its test code. */
    public record AnalyteKey(UUID labOrderId, String testCode) {
    }

    /**
     * The analytes among these rows that already have a released result.
     * Computed once per read and passed to {@link #isSupersededByRelease}.
     */
    public static Set<AnalyteKey> releasedAnalytes(Collection<LabResult> rows) {
        Set<AnalyteKey> released = new HashSet<>();
        if (rows == null) {
            return released;
        }
        for (LabResult row : rows) {
            if (row != null && row.isReleased()) {
                AnalyteKey key = analyteKey(row);
                if (key != null) {
                    released.add(key);
                }
            }
        }
        return released;
    }

    /**
     * Whether this row is an unreleased result for an analyte that already has
     * a released one — the preliminary the final has left behind. A released
     * row is never superseded, and neither is a row without an analyte.
     */
    public static boolean isSupersededByRelease(LabResult row, Set<AnalyteKey> releasedAnalytes) {
        if (row == null || row.isReleased() || releasedAnalytes == null || releasedAnalytes.isEmpty()) {
            return false;
        }
        AnalyteKey key = analyteKey(row);
        return key != null && releasedAnalytes.contains(key);
    }

    private static AnalyteKey analyteKey(LabResult row) {
        LabOrder order = row.getLabOrder();
        String testCode = row.getTestCode();
        if (order == null || order.getId() == null || testCode == null || testCode.isBlank()) {
            return null;
        }
        return new AnalyteKey(order.getId(), testCode);
    }
}
