package com.example.hms.repository;

import com.example.hms.model.FrontendAuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface FrontendAuditEventRepository extends JpaRepository<FrontendAuditEvent, UUID> {

    /**
     * MVP-8c — bounded date-range slice for the cross-source aggregation.
     * Both bounds are nullable; null means "no bound on that side".
     * The {@link Pageable} caller is expected to pass a sensible page
     * size (the aggregation service caps it server-side).
     */
    @Query("SELECT f FROM FrontendAuditEvent f WHERE "
        + "(:fromDate IS NULL OR f.occurredAt >= :fromDate) AND "
        + "(:toDate   IS NULL OR f.occurredAt <= :toDate) "
        + "ORDER BY f.occurredAt DESC")
    List<FrontendAuditEvent> findInDateRangeOrdered(
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate,
        Pageable pageable);

    @Query("SELECT COUNT(f) FROM FrontendAuditEvent f WHERE "
        + "(:fromDate IS NULL OR f.occurredAt >= :fromDate) AND "
        + "(:toDate   IS NULL OR f.occurredAt <= :toDate)")
    long countInDateRange(
        @Param("fromDate") LocalDateTime fromDate,
        @Param("toDate") LocalDateTime toDate);
}
