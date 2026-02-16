package com.example.hms.payload.event;

import com.example.hms.enums.platform.PlatformRegistryEventType;
import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

/**
 * Payload describing significant lifecycle events emitted by the platform registry module.
 */
@Getter
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlatformServiceEventPayload {

    private final PlatformRegistryEventType eventType;
    private final UUID organizationId;
    private final UUID organizationServiceId;
    private final UUID hospitalId;
    private final UUID departmentId;
    private final PlatformServiceType serviceType;
    private final PlatformServiceStatus status;
    private final Boolean linkEnabled;
    private final boolean managedByPlatform;
    private final String provider;
    private final String baseUrl;
    private final String documentationUrl;
    private final String apiKeyReference;
    private final String ownershipTeam;
    private final String ownershipContactEmail;
    private final String ownershipServiceLevel;
    private final String ownershipDataSteward;
    private final String metadataEhrSystem;
    private final String metadataBillingSystem;
    private final String metadataInventorySystem;
    private final String metadataIntegrationNotes;
    private final UUID triggeredBy;
    private final Instant occurredAt;

    /**
     * Derives a deterministic event key for partition placement.
     */
    public String eventKey() {
        if (organizationServiceId != null) {
            return organizationServiceId.toString();
        }
        if (organizationId != null) {
            return organizationId.toString();
        }
        return serviceType != null ? serviceType.name() : "platform-registry";
    }
}
