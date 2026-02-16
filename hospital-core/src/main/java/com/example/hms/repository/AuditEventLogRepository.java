package com.example.hms.repository;

import com.example.hms.enums.AuditEventType;
import com.example.hms.model.AuditEventLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.example.hms.enums.AuditStatus;

import java.util.UUID;

@Repository
public interface AuditEventLogRepository extends JpaRepository<AuditEventLog, UUID> {

    Page<AuditEventLog> findByUserId(UUID userId, Pageable pageable);

    Page<AuditEventLog> findByEventType(AuditEventType eventType, Pageable pageable);

    Page<AuditEventLog> findByEntityTypeIgnoreCaseAndResourceId(
        String entityType, String resourceId, Pageable pageable);


    Page<AuditEventLog> findByEventTypeAndStatus(AuditEventType type, AuditStatus status, Pageable pageable);

    /** Ordered descending by eventTimestamp (index advisable). */
    Page<AuditEventLog> findAllByOrderByEventTimestampDesc(Pageable pageable);
}
