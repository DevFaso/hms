package com.example.hms.service.integration.impl;

import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.integration.Dhis2ExportRun;
import com.example.hms.model.integration.Dhis2FacilityConfig;
import com.example.hms.model.integration.Dhis2PeriodType;
import com.example.hms.repository.integration.Dhis2FacilityConfigRepository;
import com.example.hms.service.integration.DhisAdxAggregator;
import com.example.hms.service.integration.DhisAdxExportService;
import com.example.hms.service.integration.DhisAdxXmlWriter;
import com.example.hms.service.integration.DhisHttpClient;
import com.example.hms.service.integration.DhisHttpResponse;
import com.example.hms.service.integration.PeriodResolver;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one DHIS2 ADX export end-to-end. The persisted state
 * machine on {@link Dhis2ExportRun}:
 *
 * <pre>
 *   PENDING -> SUCCESS    (DHIS2 returned 2xx and reported zero ignored)
 *   PENDING -> PARTIAL    (DHIS2 returned 2xx but ignored &gt; 0)
 *   PENDING -> FAILED     (any 4xx / 5xx / network error)
 * </pre>
 *
 * <p>Persistence is delegated to {@link Dhis2ExportRunPersistence} so
 * Spring's transactional AOP proxy actually fires the
 * {@code REQUIRES_NEW} boundary around each write step (Sonar S6809).
 */
@Service
public class DhisAdxExportServiceImpl implements DhisAdxExportService {

    private static final Logger log = LoggerFactory.getLogger(DhisAdxExportServiceImpl.class);

    private final Dhis2FacilityConfigRepository facilityConfigRepository;
    private final Dhis2ExportRunPersistence persistence;
    private final DhisAdxAggregator aggregator;
    private final DhisAdxXmlWriter xmlWriter;
    private final DhisHttpClient httpClient;

    public DhisAdxExportServiceImpl(Dhis2FacilityConfigRepository facilityConfigRepository,
                                    Dhis2ExportRunPersistence persistence,
                                    DhisAdxAggregator aggregator,
                                    DhisAdxXmlWriter xmlWriter,
                                    DhisHttpClient httpClient) {
        this.facilityConfigRepository = facilityConfigRepository;
        this.persistence = persistence;
        this.aggregator = aggregator;
        this.xmlWriter = xmlWriter;
        this.httpClient = httpClient;
    }

    @Override
    public Dhis2ExportRun triggerImmunizationsExport(UUID hospitalId,
                                                     String datasetUid,
                                                     Dhis2PeriodType periodType,
                                                     String periodIso,
                                                     UUID staffId) {
        final Dhis2FacilityConfig config = facilityConfigRepository
            .findByHospital_IdAndActiveTrue(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "No active DHIS2 facility config for hospital " + hospitalId));

        if (config.getDefaultPeriodType() != periodType) {
            throw new BusinessException(
                "periodType " + periodType + " does not match facility default "
                    + config.getDefaultPeriodType());
        }

        final PeriodResolver.Range range = PeriodResolver.resolve(periodType, periodIso);

        final DhisAdxAggregator.AggregationResult aggregated =
            aggregator.aggregateImmunizations(hospitalId, datasetUid, periodType,
                range.start(), range.endInclusive());

        final Dhis2ExportRun run = persistence.persistPending(hospitalId, datasetUid, periodIso,
            staffId, aggregated);

        if (aggregated.values().isEmpty()) {
            log.info("DHIS2 export run {}: nothing to send for hospital={} period={}",
                run.getId(), hospitalId, periodIso);
            return persistence.finalizeEmpty(run);
        }

        final String adxXml = xmlWriter.build(
            aggregated.orgUnitUid(),
            periodIso,
            datasetUid,
            aggregated.values(),
            OffsetDateTime.now());

        final DhisHttpResponse response;
        try {
            response = httpClient.postDataValueSet(
                config.getBaseUrl(),
                config.getAuthMode(),
                config.getAuthSecretEnvVar(),
                adxXml,
                run.getRequestId());
        } catch (RuntimeException e) {
            return persistence.finalizeFailed(run, 0, "Unhandled push error: " + e.getMessage());
        }

        return persistence.finalizeReconciled(run, config, response);
    }
}
