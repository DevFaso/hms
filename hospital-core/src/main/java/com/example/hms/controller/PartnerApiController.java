package com.example.hms.controller;

import com.example.hms.payload.dto.platform.WebhookDeliveryResponseDTO;
import com.example.hms.security.ApiKeyAuthenticationFilter;
import com.example.hms.service.apikey.ApiKeyService.ApiKeyAuth;
import com.example.hms.service.webhook.WebhookEndpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * The surface an issued API key authenticates (Tier 2 item 45) — the
 * verify path's real consumer, so key verification is exercised end to
 * end from day one (the built-but-unreachable defect class).
 *
 * <p>Authenticated by {@link ApiKeyAuthenticationFilter} from the
 * {@code X-API-Key} header; the principal is the verified key's identity
 * and pins the hospital scope. Deliberately small and PHI-free: partners
 * confirm their credential works and see their own webhook delivery log.
 */
@RestController
@RequestMapping(value = "/partner", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('" + ApiKeyAuthenticationFilter.ROLE_PARTNER_API + "')")
@Tag(name = "Partner API", description = "Third-party client surface, X-API-Key authenticated")
// Overrides the global BearerAuth requirement: generated clients and
// Swagger UI must advertise the X-API-Key header, not a JWT this surface
// rejects.
@io.swagger.v3.oas.annotations.security.SecurityRequirements(
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = com.example.hms.config.OpenApiConfig.API_KEY_SCHEME_NAME))
public class PartnerApiController {

    private final WebhookEndpointService webhookEndpointService;

    @Operation(summary = "Prove the API key works",
        description = "Returns the key's label and the server time - the connectivity check.")
    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping(@AuthenticationPrincipal ApiKeyAuth auth) {
        return ResponseEntity.ok(Map.of(
            "keyLabel", auth.label(),
            "serverTime", Instant.now().toString()));
    }

    @Operation(summary = "This hospital's webhook delivery log, newest first",
        description = "Thin id-reference payloads only - the same rows the webhook carried.")
    @GetMapping("/webhook-deliveries")
    public ResponseEntity<Page<WebhookDeliveryResponseDTO>> webhookDeliveries(
            @AuthenticationPrincipal ApiKeyAuth auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(
            webhookEndpointService.deliveriesForHospital(auth.hospitalId(), page, size));
    }
}
