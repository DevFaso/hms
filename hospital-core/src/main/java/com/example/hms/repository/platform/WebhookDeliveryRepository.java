package com.example.hms.repository.platform;

import com.example.hms.enums.platform.WebhookDeliveryStatus;
import com.example.hms.model.platform.WebhookDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    /**
     * The dispatch sweep (instrument-outbox mechanics): pending rows under
     * the attempt ceiling whose retry window has elapsed, oldest first.
     */
    @Query("select d from WebhookDelivery d where d.status = :status "
        + "and d.attempts < :maxAttempts "
        + "and (d.lastAttemptAt is null or d.lastAttemptAt < :retryBefore) "
        + "order by d.createdAt asc")
    List<WebhookDelivery> findDispatchable(@Param("status") WebhookDeliveryStatus status,
                                           @Param("maxAttempts") int maxAttempts,
                                           @Param("retryBefore") LocalDateTime retryBefore,
                                           Pageable pageable);

    /** One endpoint's delivery log, newest first — the portal drilldown. */
    @EntityGraph(attributePaths = "endpoint")
    Page<WebhookDelivery> findByEndpoint_IdOrderByCreatedAtDesc(UUID endpointId, Pageable pageable);

    /** One hospital's recent deliveries, newest first — the partner API read. */
    @EntityGraph(attributePaths = "endpoint")
    Page<WebhookDelivery> findByEndpoint_Hospital_IdOrderByCreatedAtDesc(
        UUID hospitalId, Pageable pageable);
}
