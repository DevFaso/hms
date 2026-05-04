package com.example.hms.repository;

import com.example.hms.enums.PermissionMatrixAuditAction;
import com.example.hms.model.PermissionMatrixAuditEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PermissionMatrixAuditEventRepository extends JpaRepository<PermissionMatrixAuditEvent, UUID> {

    List<PermissionMatrixAuditEvent> findTop50ByOrderByCreatedAtDesc();

    List<PermissionMatrixAuditEvent> findTop50ByActionOrderByCreatedAtDesc(PermissionMatrixAuditAction action);

    /**
     * MVP-8c — bounded date-range slice for the cross-source aggregation.
     * Note: this entity uses {@link Instant} for {@code createdAt}, so
     * the service converts the LocalDateTime filter bounds to UTC
     * Instants before calling.
     */
    @Query("SELECT p FROM PermissionMatrixAuditEvent p WHERE "
        + "(:fromInstant IS NULL OR p.createdAt >= :fromInstant) AND "
        + "(:toInstant   IS NULL OR p.createdAt <= :toInstant) "
        + "ORDER BY p.createdAt DESC")
    List<PermissionMatrixAuditEvent> findInDateRangeOrdered(
        @Param("fromInstant") Instant fromInstant,
        @Param("toInstant") Instant toInstant,
        Pageable pageable);

    @Query("SELECT COUNT(p) FROM PermissionMatrixAuditEvent p WHERE "
        + "(:fromInstant IS NULL OR p.createdAt >= :fromInstant) AND "
        + "(:toInstant   IS NULL OR p.createdAt <= :toInstant)")
    long countInDateRange(
        @Param("fromInstant") Instant fromInstant,
        @Param("toInstant") Instant toInstant);
}