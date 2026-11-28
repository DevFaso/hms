package com.example.hms.payload.dto.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Insurer claims plan PR 4 (US-16, US-17): what the payers owe a hospital. Whole francs.
 */
public final class InsurerReceivablesDTO {

    private InsurerReceivablesDTO() {
    }

    /**
     * One payer's receivable on {@code asOf}: what is not yet on a submitted bordereau, then what is outstanding on
     * submitted bordereaux by days since they were sent. {@code outstanding} is the sum of the four buckets;
     * {@code writeOffPending} is the part of it a write-off request holds; {@code unallocated} is money received from
     * the payer and not yet put on a line.
     */
    public record AgeingRow(UUID payerId, String payerName, BigDecimal notClaimed, BigDecimal days0To30,
                            BigDecimal days31To60, BigDecimal days61To90, BigDecimal over90Days,
                            BigDecimal outstanding, BigDecimal writeOffPending, BigDecimal unallocated) {
    }

    /** The ageing of every payer of the hospital, and the totals row. */
    public record Ageing(LocalDate asOf, List<AgeingRow> rows, AgeingRow total) {
    }

    /**
     * One payer's bordereaux sent in a period: {@code claimed = paid + rebilled + writtenOff + outstanding}.
     * {@code received} is the money the payer remitted in the same period and {@code unallocated} the part of it not
     * yet put on a line.
     */
    public record ReconciliationRow(UUID payerId, String payerName, int batchCount, BigDecimal claimed,
                                    BigDecimal paid, BigDecimal rebilled, BigDecimal writtenOff,
                                    BigDecimal writeOffPending, BigDecimal outstanding, BigDecimal received,
                                    BigDecimal unallocated) {
    }

    /** The reconciliation of the hospital's payers over a period, and the totals row. */
    public record Reconciliation(LocalDate fromDate, LocalDate toDate, List<ReconciliationRow> rows,
                                 ReconciliationRow total) {
    }
}
