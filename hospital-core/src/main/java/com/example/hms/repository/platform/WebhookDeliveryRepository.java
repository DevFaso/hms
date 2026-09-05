package com.example.hms.repository.platform;

import com.example.hms.enums.platform.WebhookDeliveryStatus;
import com.example.hms.model.platform.WebhookDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
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
     * The dispatch sweep's candidate list: pending rows under the attempt
     * ceiling whose retry window has elapsed, oldest first. Ids only —
     * each row is then CLAIMED individually before any network work.
     */
    @Query("select d.id from WebhookDelivery d where d.status = :status "
        + "and d.attempts < :maxAttempts "
        + "and (d.lastAttemptAt is null or d.lastAttemptAt < :retryBefore) "
        + "order by d.createdAt asc")
    List<UUID> findDispatchableIds(@Param("status") WebhookDeliveryStatus status,
                                   @Param("maxAttempts") int maxAttempts,
                                   @Param("retryBefore") LocalDateTime retryBefore,
                                   Pageable pageable);

    /**
     * The atomic claim — the house conditional-update idiom (this codebase
     * runs without ShedLock): bumping attempts/lastAttemptAt only while
     * the row still matches the dispatchable predicate means exactly one
     * instance wins a row; the loser's UPDATE matches zero rows and it
     * moves on. No delivery is ever sent twice by concurrent sweeps.
     */
    @Modifying
    @Query("update WebhookDelivery d set d.attempts = d.attempts + 1, d.lastAttemptAt = :now "
        + "where d.id = :id and d.status = :status "
        + "and d.attempts < :maxAttempts "
        + "and (d.lastAttemptAt is null or d.lastAttemptAt < :retryBefore)")
    int claim(@Param("id") UUID id,
              @Param("status") WebhookDeliveryStatus status,
              @Param("maxAttempts") int maxAttempts,
              @Param("retryBefore") LocalDateTime retryBefore,
              @Param("now") LocalDateTime now);

    /** One endpoint's delivery log, newest first — the portal drilldown. */
    @EntityGraph(attributePaths = "endpoint")
    Page<WebhookDelivery> findByEndpoint_IdOrderByCreatedAtDesc(UUID endpointId, Pageable pageable);

    /** One hospital's recent deliveries, newest first — the partner API read. */
    @EntityGraph(attributePaths = "endpoint")
    Page<WebhookDelivery> findByEndpoint_Hospital_IdOrderByCreatedAtDesc(
        UUID hospitalId, Pageable pageable);
}
