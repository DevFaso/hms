package com.example.hms.controller;

import com.example.hms.payload.dto.platform.WebhookDeliveryResponseDTO;
import com.example.hms.payload.dto.platform.WebhookEndpointRegisteredDTO;
import com.example.hms.payload.dto.platform.WebhookEndpointRequestDTO;
import com.example.hms.payload.dto.platform.WebhookEndpointResponseDTO;
import com.example.hms.service.webhook.WebhookEndpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Outbound webhook endpoints (Tier 2 item 45): register, update,
 * pause/resume, revoke, rotate the signing secret, ping, and read the
 * delivery log. Hospital-scoped; the signing secret appears only in the
 * register/rotate-secret responses.
 */
@RestController
@RequestMapping(value = "/webhook-endpoints", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN','ROLE_IT_STAFF')")
@Tag(name = "Webhook Endpoints", description = "Outbound event notifications (Tier 2 item 45)")
@SecurityRequirement(name = "bearerAuth")
public class WebhookEndpointController {

    private final WebhookEndpointService endpointService;

    @Operation(summary = "List this hospital's webhook endpoints")
    @GetMapping
    public ResponseEntity<List<WebhookEndpointResponseDTO>> list() {
        return ResponseEntity.ok(endpointService.list());
    }

    @Operation(summary = "Register a webhook endpoint",
        description = "HTTPS public hosts only. The response carries the signing secret ONCE.")
    @PostMapping
    public ResponseEntity<WebhookEndpointRegisteredDTO> register(
            @Valid @RequestBody WebhookEndpointRequestDTO request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(endpointService.register(request));
    }

    @Operation(summary = "Update a webhook endpoint's URL, description or subscriptions")
    @PutMapping("/{endpointId}")
    public ResponseEntity<WebhookEndpointResponseDTO> update(
            @PathVariable UUID endpointId,
            @Valid @RequestBody WebhookEndpointRequestDTO request) {
        return ResponseEntity.ok(endpointService.update(endpointId, request));
    }

    @Operation(summary = "Pause or resume deliveries",
        description = "Resume also clears the consecutive-failure strike count, so it is "
            + "the way back from an auto-disable.")
    @PutMapping("/{endpointId}/active")
    public ResponseEntity<WebhookEndpointResponseDTO> setActive(
            @PathVariable UUID endpointId, @RequestParam boolean active) {
        return ResponseEntity.ok(endpointService.setActive(endpointId, active));
    }

    @Operation(summary = "Revoke a webhook endpoint",
        description = "Permanent - the delivery history stays readable.")
    @PutMapping("/{endpointId}/revoke")
    public ResponseEntity<WebhookEndpointResponseDTO> revoke(@PathVariable UUID endpointId) {
        return ResponseEntity.ok(endpointService.revoke(endpointId));
    }

    @Operation(summary = "Rotate the signing secret",
        description = "The old secret stops verifying immediately; the new one appears ONCE.")
    @PostMapping("/{endpointId}/rotate-secret")
    public ResponseEntity<WebhookEndpointRegisteredDTO> rotateSecret(@PathVariable UUID endpointId) {
        return ResponseEntity.ok(endpointService.rotateSecret(endpointId));
    }

    @Operation(summary = "Send a test PING delivery",
        description = "Enqueued like any event and swept by the dispatcher - proves the "
            + "wiring, the signature and the receiver end to end.")
    @PostMapping("/{endpointId}/ping")
    public ResponseEntity<WebhookDeliveryResponseDTO> ping(@PathVariable UUID endpointId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(endpointService.ping(endpointId));
    }

    @Operation(summary = "One endpoint's delivery log, newest first")
    @GetMapping("/{endpointId}/deliveries")
    public ResponseEntity<Page<WebhookDeliveryResponseDTO>> deliveries(
            @PathVariable UUID endpointId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(endpointService.deliveries(endpointId, page, size));
    }
}
