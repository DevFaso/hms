package com.example.hms.repository.integration;

import com.example.hms.model.integration.Dhis2DataElementMapping;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface Dhis2DataElementMappingRepository
        extends JpaRepository<Dhis2DataElementMapping, UUID> {

    Page<Dhis2DataElementMapping> findByHospital_IdAndDatasetUid(
        UUID hospitalId, String datasetUid, Pageable pageable);

    List<Dhis2DataElementMapping> findByHospital_IdAndDatasetUidAndActiveTrue(
        UUID hospitalId, String datasetUid);

    /**
     * Batch resolution used by {@code DhisAdxAggregator} so the per-code
     * lookup is one query rather than N. Filters to active mappings.
     */
    List<Dhis2DataElementMapping> findByHospital_IdAndDatasetUidAndHmsConceptSystemAndHmsConceptCodeInAndActiveTrue(
        UUID hospitalId, String datasetUid, String hmsConceptSystem,
        Collection<String> hmsConceptCodes);

    Optional<Dhis2DataElementMapping> findByHospital_IdAndHmsConceptSystemAndHmsConceptCodeAndDatasetUid(
        UUID hospitalId, String hmsConceptSystem, String hmsConceptCode, String datasetUid);
}
