package com.example.hms.service.lab;

import com.example.hms.model.LabResult;
import java.util.List;
import java.util.UUID;
import java.util.Set;

/** Both result-release paths decide from the same readiness rule under the order lock. */
public final class LabOrderCompletion {
    private LabOrderCompletion() { }

    public static boolean allReleased(List<LabResult> results, boolean culturesFinished, boolean hasFinalizedCulture) {
        if (!culturesFinished || (results.isEmpty() && !hasFinalizedCulture)) return false;
        Set<UUID> superseded = SupersededLabResults.supersededRowIds(results, results);
        return results.stream().allMatch(row -> row.isReleased() || superseded.contains(row.getId()));
    }
}
