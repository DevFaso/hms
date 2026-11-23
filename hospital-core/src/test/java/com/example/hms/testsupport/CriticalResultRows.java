package com.example.hms.testsupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.lenient;

import com.example.hms.model.LabResult;
import com.example.hms.repository.LabResultRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * QA r70 (C5): a mocked {@link LabResultRepository} that answers the escalation sweep's two conditional UPDATEs
 * as the table would. The sweep never saves the result it loaded (a save would write every column as it was
 * read, an acknowledgement committed meanwhile included); it stamps a step, or clears the stamps, only while the
 * row is unacknowledged. In a unit test the result object plays the row: the stubs apply the same WHERE to it
 * and write the same columns, so a test still reads the level and the stamps off the result.
 */
public final class CriticalResultRows {

    private final Map<UUID, LabResult> rows = new HashMap<>();

    private CriticalResultRows() {
    }

    /** Stubs the two conditional UPDATEs of {@code repository} over the rows given to {@link #with}. */
    public static CriticalResultRows of(LabResultRepository repository) {
        CriticalResultRows table = new CriticalResultRows();
        lenient().when(repository.stampEscalationIfUnacknowledged(any(), anyShort(), anyShort(), any()))
            .thenAnswer(call -> {
                LabResult row = table.rows.get(call.<UUID>getArgument(0));
                short expected = call.getArgument(1);
                if (table.acknowledgedOrGone(row) || row.getCriticalNotifiedAt() == null
                        || row.getCriticalEscalationLevel() != expected) {
                    return 0;
                }
                row.setCriticalEscalationLevel(call.<Short>getArgument(2));
                row.setCriticalEscalatedAt(call.getArgument(3));
                return 1;
            });
        lenient().when(repository.leaveCriticalChainIfUnacknowledged(any(), anyShort())).thenAnswer(call -> {
            LabResult row = table.rows.get(call.<UUID>getArgument(0));
            if (table.acknowledgedOrGone(row)) {
                return 0;
            }
            row.setCriticalNotifiedAt(null);
            row.setCriticalEscalatedAt(null);
            row.setCriticalEscalationLevel(call.<Short>getArgument(1));
            return 1;
        });
        return table;
    }

    public CriticalResultRows with(LabResult... results) {
        for (LabResult result : results) {
            rows.put(result.getId(), result);
        }
        return this;
    }

    private boolean acknowledgedOrGone(LabResult row) {
        return row == null || row.isAcknowledged() || row.getCriticalReadBackAt() != null;
    }
}
