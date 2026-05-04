package com.example.hms.repository.integration;

import com.example.hms.model.integration.IntegrationHealthEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface IntegrationHealthEventRepository extends JpaRepository<IntegrationHealthEvent, UUID> {

    @Query("""
        SELECT e FROM IntegrationHealthEvent e
         WHERE e.integrationId = :integrationId
           AND e.recordedAt >= :since
         ORDER BY e.recordedAt DESC
        """)
    List<IntegrationHealthEvent> findRecentForIntegration(
        @Param("integrationId") String integrationId,
        @Param("since") LocalDateTime since);
}
