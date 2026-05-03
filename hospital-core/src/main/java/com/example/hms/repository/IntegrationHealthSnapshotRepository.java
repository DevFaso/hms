package com.example.hms.repository;

import com.example.hms.enums.integration.IntegrationHealthStatus;
import com.example.hms.model.integration.IntegrationHealthSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IntegrationHealthSnapshotRepository
    extends JpaRepository<IntegrationHealthSnapshot, UUID> {

    /** Recorder upsert lookup. {@code organizationId} may be null for platform-wide snapshots. */
    @Query("""
        SELECT s FROM IntegrationHealthSnapshot s
         WHERE s.integrationId = :integrationId
           AND ((:organizationId IS NULL AND s.organization IS NULL)
                OR (:organizationId IS NOT NULL AND s.organization.id = :organizationId))
        """)
    Optional<IntegrationHealthSnapshot> findOneFor(
        @Param("integrationId") String integrationId,
        @Param("organizationId") UUID organizationId);

    /** Console grid — every snapshot for one organization. */
    List<IntegrationHealthSnapshot> findByOrganization_IdOrderByLastStatusAscIntegrationIdAsc(
        UUID organizationId);

    /** Per-integration drill-down across all orgs. */
    List<IntegrationHealthSnapshot> findByIntegrationIdOrderByLastStatusAscUpdatedAtDesc(
        String integrationId);

    /** Inventory grid — every snapshot. Bounded by # integrations × # organizations, so unpaginated for MVP. */
    List<IntegrationHealthSnapshot> findAllByOrderByIntegrationIdAscLastStatusAsc();

    /** Control-tower tile count of failing integrations. */
    long countByLastStatus(IntegrationHealthStatus status);
}
