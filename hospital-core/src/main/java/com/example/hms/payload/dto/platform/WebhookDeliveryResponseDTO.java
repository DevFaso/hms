package com.example.hms.payload.dto.platform;

import com.example.hms.enums.platform.WebhookDeliveryStatus;
import com.example.hms.enums.platform.WebhookEventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One webhook delivery (Tier 2 item 45), for the portal drilldown and the
 * partner API alike — the payload is thin id-references, safe for both.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookDeliveryResponseDTO {

    private UUID id;
    private UUID endpointId;
    private String endpointUrl;
    private WebhookEventType eventType;
    private WebhookDeliveryStatus status;
    private int attempts;
    private Integer responseStatus;
    private String lastError;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime sentAt;
    private LocalDateTime createdAt;
    private String payload;
}
