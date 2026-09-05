package com.example.hms.repository.platform;

import com.example.hms.enums.platform.WebhookEndpointStatus;
import com.example.hms.enums.platform.WebhookEventType;
import com.example.hms.model.platform.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    /** The admin listing: one hospital's endpoints, newest first. */
    List<WebhookEndpoint> findByHospital_IdOrderByCreatedAtDesc(UUID hospitalId);

    /** The fan-out target set for one event at one hospital. */
    @Query("select e from WebhookEndpoint e where e.hospital.id = :hospitalId "
        + "and e.status = :status and :eventType member of e.subscribedEvents")
    List<WebhookEndpoint> findSubscribed(@Param("hospitalId") UUID hospitalId,
                                         @Param("status") WebhookEndpointStatus status,
                                         @Param("eventType") WebhookEventType eventType);
}
