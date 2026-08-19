package com.example.hms.service.integration.impl;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.integration.Dhis2DataElementMapping;
import com.example.hms.model.integration.Dhis2PeriodType;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.ImmunizationRepository;
import com.example.hms.repository.integration.Dhis2DataElementMappingRepository;
import com.example.hms.service.integration.AggregatedDataValue;
import com.example.hms.service.integration.DhisAdxAggregator;
import com.example.hms.terminology.TerminologyCodes;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * v0 aggregator: counts completed, active immunizations per CVX code
 * for a hospital within a date range, then resolves each CVX code to
 * its DHIS2 dataElement UID via {@code dhis2_dataelement_mapping}.
 *
 * <p>Codes without a mapping are skipped (logged + counted) — the
 * orchestrator records the skipped count on {@code dhis2_export_run}
 * so operators can see whether their mapping table is up-to-date.
 */
@Service
@Transactional(readOnly = true)
public class DhisAdxAggregatorImpl implements DhisAdxAggregator {

    private static final Logger log = LoggerFactory.getLogger(DhisAdxAggregatorImpl.class);

    private final ImmunizationRepository immunizationRepository;
    private final Dhis2DataElementMappingRepository mappingRepository;
    private final HospitalRepository hospitalRepository;

    public DhisAdxAggregatorImpl(ImmunizationRepository immunizationRepository,
                                 Dhis2DataElementMappingRepository mappingRepository,
                                 HospitalRepository hospitalRepository) {
        this.immunizationRepository = immunizationRepository;
        this.mappingRepository = mappingRepository;
        this.hospitalRepository = hospitalRepository;
    }

    @Override
    public AggregationResult aggregateImmunizations(UUID hospitalId,
                                                    String datasetUid,
                                                    Dhis2PeriodType periodType,
                                                    LocalDate periodStart,
                                                    LocalDate periodEnd) {
        final Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Hospital not found: " + hospitalId));

        final String orgUnitUid = hospital.getDhis2OrgUnitUid();
        if (orgUnitUid == null || orgUnitUid.isBlank()) {
            throw new IllegalStateException(
                "Hospital " + hospitalId + " has no DHIS2 organisation-unit UID configured");
        }

        // [hospitalId, range] -> list of [vaccineCode, count]
        final List<Object[]> rawCounts = immunizationRepository
            .countByVaccineCodeForHospitalInRange(hospitalId, periodStart, periodEnd);
        if (rawCounts.isEmpty()) {
            log.info("DHIS2 ADX aggregator: no immunizations for hospital={} period={}..{}",
                hospitalId, periodStart, periodEnd);
            return new AggregationResult(List.of(), 0, orgUnitUid);
        }

        // Batch resolve CVX -> DHIS2 dataElement UID in one query.
        final Set<String> cvxCodes = new HashSet<>();
        for (Object[] row : rawCounts) {
            cvxCodes.add((String) row[0]);
        }

        final List<Dhis2DataElementMapping> mappings = mappingRepository
            .findByHospital_IdAndDatasetUidAndHmsConceptSystemAndHmsConceptCodeInAndActiveTrue(
                hospitalId, datasetUid, TerminologyCodes.SYSTEM_CVX, cvxCodes);

        // Filter by periodType so a mapping authored for a different
        // granularity (e.g. weekly mapping when this run is monthly) is
        // not silently used. Mappings are period-type-scoped per the
        // service contract and the V68 column.
        final Map<String, Dhis2DataElementMapping> mappingByCvx = new HashMap<>();
        for (Dhis2DataElementMapping m : mappings) {
            if (m.getPeriodType() == periodType) {
                mappingByCvx.put(m.getHmsConceptCode(), m);
            }
        }

        final List<AggregatedDataValue> values = new ArrayList<>();
        int skipped = 0;
        for (Object[] row : rawCounts) {
            final String cvx = (String) row[0];
            final Long count = (Long) row[1];
            final Dhis2DataElementMapping mapping = mappingByCvx.get(cvx);
            if (mapping == null) {
                log.info("DHIS2 ADX aggregator: skipping CVX={} (no {} mapping for dataset {})",
                    cvx, periodType, datasetUid);
                skipped++;
                continue;
            }
            values.add(new AggregatedDataValue(
                orgUnitUid,
                mapping.getDhis2DataElementUid(),
                mapping.getDhis2CategoryOptionComboUid(),
                Long.toString(count)));
        }

        return new AggregationResult(values, skipped, orgUnitUid);
    }
}
