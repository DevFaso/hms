package com.example.hms.payload.dto.platform;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The registration/secret-rotation response (Tier 2 item 45) — the ONLY
 * place the signing secret ever appears. It is encrypted at rest and
 * cannot be shown again.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEndpointRegisteredDTO {

    private WebhookEndpointResponseDTO endpoint;
    private String secret;
}
