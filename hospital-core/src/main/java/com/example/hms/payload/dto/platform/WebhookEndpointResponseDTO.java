package com.example.hms.payload.dto.platform;

import com.example.hms.enums.platform.WebhookEndpointStatus;
import com.example.hms.enums.platform.WebhookEventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * One webhook endpoint (Tier 2 item 45). The signing secret is never in
 * a read response — it appears once, at registration or rotation.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEndpointResponseDTO {

    private UUID id;
    private String url;
    private String description;
    private WebhookEndpointStatus status;
    private Set<WebhookEventType> events;
    private int consecutiveFailures;
    private LocalDateTime createdAt;
}
