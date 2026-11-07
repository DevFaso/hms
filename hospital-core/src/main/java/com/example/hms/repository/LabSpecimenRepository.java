package com.example.hms.repository;

import com.example.hms.model.LabSpecimen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LabSpecimenRepository extends JpaRepository<LabSpecimen, UUID> {

    List<LabSpecimen> findByLabOrder_Id(UUID labOrderId);

    /** D49: the specimens of several orders at once, for their real collection times. */
    List<LabSpecimen> findByLabOrder_IdIn(java.util.Collection<UUID> labOrderIds);

    boolean existsByAccessionNumber(String accessionNumber);

    Optional<LabSpecimen> findByAccessionNumber(String accessionNumber);

    /**
     * The committed status, as a scalar projection: not served from the
     * persistence context, so it sees a write that committed after the
     * entity was loaded (review of #975).
     */
    @org.springframework.data.jpa.repository.Query("select s.status from LabSpecimen s where s.id = :id")
    com.example.hms.enums.LabSpecimenStatus findStatusById(@org.springframework.data.repository.query.Param("id") UUID id);
}
