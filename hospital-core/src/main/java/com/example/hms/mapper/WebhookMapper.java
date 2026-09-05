package com.example.hms.mapper;

import com.example.hms.model.platform.WebhookDelivery;
import com.example.hms.model.platform.WebhookEndpoint;
import com.example.hms.payload.dto.platform.WebhookDeliveryResponseDTO;
import com.example.hms.payload.dto.platform.WebhookEndpointResponseDTO;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Webhook rows → DTOs (Tier 2 item 45), per the house mapper convention.
 * Never maps the signing secret — it appears once, at registration or
 * rotation, and nowhere else.
 */
@Component
public class WebhookMapper {

    public WebhookEndpointResponseDTO toDto(WebhookEndpoint e) {
        if (e == null) {
            return null;
        }
        Set<com.example.hms.enums.platform.WebhookEventType> events =
            e.getSubscribedEvents() == null || e.getSubscribedEvents().isEmpty()
                ? Set.of()
                : EnumSet.copyOf(e.getSubscribedEvents());
        return WebhookEndpointResponseDTO.builder()
            .id(e.getId())
            .url(e.getUrl())
            .description(e.getDescription())
            .status(e.getStatus())
            .events(events)
            .consecutiveFailures(e.getConsecutiveFailures())
            .createdAt(e.getCreatedAt())
            .build();
    }

    public WebhookDeliveryResponseDTO toDto(WebhookDelivery d) {
        if (d == null) {
            return null;
        }
        return WebhookDeliveryResponseDTO.builder()
            .id(d.getId())
            .endpointId(d.getEndpoint() != null ? d.getEndpoint().getId() : null)
            .endpointUrl(d.getEndpoint() != null ? d.getEndpoint().getUrl() : null)
            .eventType(d.getEventType())
            .status(d.getStatus())
            .attempts(d.getAttempts())
            .responseStatus(d.getResponseStatus())
            .lastError(d.getLastError())
            .lastAttemptAt(d.getLastAttemptAt())
            .sentAt(d.getSentAt())
            .createdAt(d.getCreatedAt())
            .payload(d.getPayload())
            .build();
    }
}
