package com.example.hms.repository;

import com.example.hms.model.platform.FhirBulkExportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FhirBulkExportJobRepository extends JpaRepository<FhirBulkExportJob, UUID> {

    /** Scoped single-row load — cross-tenant rejection collapses to empty. */
    Optional<FhirBulkExportJob> findByIdAndHospitalId(UUID id, UUID hospitalId);

    /** The runner's sweep selection, oldest first. Unscoped — system actor. */
    List<FhirBulkExportJob> findByStatusOrderByRequestedAtAsc(FhirBulkExportJob.Status status);

    long countByStatusIn(java.util.Collection<FhirBulkExportJob.Status> statuses);

    /**
     * Atomic status flip. Returns 1 only for the caller that actually
     * moved the row from {@code expected}, so two runner ticks (or two
     * app instances — no ShedLock in this codebase) can never both
     * claim the same QUEUED job.
     */
    @Modifying
    @Query("""
            UPDATE FhirBulkExportJob j
               SET j.status = :next, j.startedAt = :now, j.updatedAt = CURRENT_TIMESTAMP
             WHERE j.id = :id
               AND j.status = :expected
    """)
    int transition(@Param("id") UUID id,
                   @Param("expected") FhirBulkExportJob.Status expected,
                   @Param("next") FhirBulkExportJob.Status next,
                   @Param("now") Instant now);

    default int claimQueued(UUID id, Instant now) {
        return transition(id, FhirBulkExportJob.Status.QUEUED,
            FhirBulkExportJob.Status.IN_PROGRESS, now);
    }
}
