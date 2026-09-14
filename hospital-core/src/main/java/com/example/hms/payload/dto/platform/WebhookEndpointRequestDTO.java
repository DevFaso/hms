package com.example.hms.payload.dto.platform;

import com.example.hms.enums.platform.WebhookEventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Set;

/** Register or update an outbound webhook endpoint (Tier 2 item 45). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEndpointRequestDTO {

    @NotBlank(message = "{webhookEndpoint.url.required}")
    @Size(max = 500)
    private String url;

    @Size(max = 255)
    private String description;

    @NotEmpty(message = "{webhookEndpoint.events.required}")
    private Set<WebhookEventType> events;
}
