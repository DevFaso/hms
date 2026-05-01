package com.example.hms.service.integration;

import com.example.hms.model.integration.Dhis2PeriodType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Builds aggregate data values to send to DHIS2 for a hospital and a
 * reporting period. v0 scope = immunization counts (CVX-coded);
 * adding maternal / outpatient buckets later means adding new
 * {@code aggregateXxx} methods, not changing this contract.
 *
 * <p><strong>Privacy contract:</strong> implementations MUST NOT
 * include any patient identifier on the returned values. Only counts,
 * sums, and DHIS2-side identifiers are permitted. A regression test
 * locks the field set on {@link AggregatedDataValue}.
 */
public interface DhisAdxAggregator {

    /** Result of one aggregation pass. */
    record AggregationResult(
            List<AggregatedDataValue> values,
            int skippedCount,
            String orgUnitUid) {
    }

    /**
     * Compute immunization counts (CVX → DHIS2 dataElement) for the
     * given hospital + dataset + period.
     *
     * @param hospitalId   hospital whose data to aggregate
     * @param datasetUid   DHIS2 dataset UID — used to find applicable mappings
     * @param periodType   monthly / weekly / yearly
     * @param periodStart  inclusive period start (caller-resolved from periodIso)
     * @param periodEnd    inclusive period end
     * @return aggregated values + count of vaccine codes that had no
     *         mapping (logged at INFO and recorded on the run row)
     */
    AggregationResult aggregateImmunizations(UUID hospitalId,
                                             String datasetUid,
                                             Dhis2PeriodType periodType,
                                             LocalDate periodStart,
                                             LocalDate periodEnd);
}
