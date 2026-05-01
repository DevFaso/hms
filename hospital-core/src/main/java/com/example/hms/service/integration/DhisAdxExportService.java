package com.example.hms.service.integration;

import com.example.hms.model.integration.Dhis2ExportRun;
import com.example.hms.model.integration.Dhis2PeriodType;
import java.util.UUID;

/**
 * High-level entry point for triggering DHIS2 ADX exports. Wraps:
 * aggregate -> persist run+outbox -> ADX XML -> HTTP POST -> reconcile.
 *
 * <p>v0 scope: immunization-only. New domains plug in by adding an
 * {@code aggregateXxx} method on {@link DhisAdxAggregator} and a
 * {@code triggerXxx} branch here.
 */
public interface DhisAdxExportService {

    /**
     * Trigger an immunization-aggregate export for one hospital + dataset
     * + period.
     *
     * @param hospitalId   tenant the export belongs to
     * @param datasetUid   DHIS2 dataset UID (must have at least one
     *                     {@link com.example.hms.model.integration.Dhis2DataElementMapping})
     * @param periodType   monthly / weekly / yearly — must match the
     *                     period_type on facility config and mapping rows
     * @param periodIso    DHIS2-canonical period token (YYYYMM | YYYYW## | YYYY); e.g. {@code 202604}
     * @param staffId      optional staff who triggered the run (null for scheduler)
     * @return persisted run with terminal status (SUCCESS / PARTIAL / FAILED)
     */
    Dhis2ExportRun triggerImmunizationsExport(UUID hospitalId,
                                              String datasetUid,
                                              Dhis2PeriodType periodType,
                                              String periodIso,
                                              UUID staffId);
}
