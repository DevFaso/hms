package com.example.hms.repository.platform;

import com.example.hms.model.platform.SystemAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface SystemAlertRepository extends JpaRepository<SystemAlert, UUID> {

    Page<SystemAlert> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<SystemAlert> findBySeverityIgnoreCaseOrderByCreatedAtDesc(String severity, Pageable pageable);

    List<SystemAlert> findByAcknowledgedFalseOrderByCreatedAtDesc();

    List<SystemAlert> findByResolvedFalseOrderByCreatedAtDesc();

    long countByAcknowledgedFalse();

    long countBySeverityIgnoreCase(String severity);

    long countByCreatedAtAfter(LocalDateTime since);

    List<SystemAlert> findByAlertTypeAndCreatedAtAfterOrderByCreatedAtDesc(String alertType, LocalDateTime since);
}
