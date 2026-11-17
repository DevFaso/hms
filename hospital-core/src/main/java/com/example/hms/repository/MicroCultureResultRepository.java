package com.example.hms.repository;

import com.example.hms.enums.MicroCultureStatus;
import com.example.hms.model.MicroCultureResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MicroCultureResultRepository extends JpaRepository<MicroCultureResult, UUID> {

    /**
     * QA CX-009: one change to a report at a time. Two people removing the two
     * isolates of a validated report each counted two, and left none. Native, on
     * the id alone, so the row the service then loads is read after the lock.
     */
    @Query(value = "SELECT CAST(id AS varchar) FROM lab.micro_culture_results WHERE id = :id FOR UPDATE",
        nativeQuery = true)
    Optional<String> lockRow(@Param("id") UUID id);

    @Query("""
            SELECT c FROM MicroCultureResult c
            WHERE c.patient.id = :patientId
                AND (:hospitalId IS NULL OR c.hospital.id = :hospitalId)
            ORDER BY c.createdAt DESC
    """)
    List<MicroCultureResult> findForPatient(@Param("patientId") UUID patientId,
                                            @Param("hospitalId") UUID hospitalId);

    @Query("""
            SELECT c FROM MicroCultureResult c
            WHERE c.hospital.id = :hospitalId
                AND (:status IS NULL OR c.status = :status)
            ORDER BY c.createdAt DESC
    """)
    Page<MicroCultureResult> findForHospital(@Param("hospitalId") UUID hospitalId,
                                             @Param("status") MicroCultureStatus status,
                                             Pageable pageable);

    List<MicroCultureResult> findByLabOrder_IdOrderByCreatedAtDesc(UUID labOrderId);

    /** FHIR DiagnosticReport search (Tier 2 item 42): one patient's cultures, tenant-scoped. */
    org.springframework.data.domain.Page<MicroCultureResult>
        findByPatient_IdAndHospital_IdOrderByCreatedAtDesc(
            UUID patientId, UUID hospitalId, org.springframework.data.domain.Pageable pageable);
}
