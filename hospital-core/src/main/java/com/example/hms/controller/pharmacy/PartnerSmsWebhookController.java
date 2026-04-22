package com.example.hms.controller.pharmacy;

import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.service.pharmacy.partner.PartnerExchangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;

/**
 * T-55 — Inbound SMS webhook for partner pharmacy replies.
 * <p>
 * Authenticated via a shared-secret header so SMS gateways without client
 * certificates can still be accepted. The secret is configured via
 * {@code pharmacy.partner.webhook.secret}; if no secret is configured the
 * endpoint refuses all requests (fail-closed).
 */
@RestController
@RequestMapping("/webhooks/partner-sms")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Partner Pharmacy Webhook",
     description = "Inbound SMS replies from partner pharmacies (Phase 7a)")
public class PartnerSmsWebhookController {

    static final String SIGNATURE_HEADER = "X-HMS-Partner-Signature";
    private static final String STATUS_KEY = "status";

    private final PartnerExchangeService exchangeService;

    @Value("${pharmacy.partner.webhook.secret:}")
    private String configuredSecret;

    @Operation(summary = "Receive an inbound partner SMS reply and route it to the exchange service")
    @PostMapping
    public ResponseEntity<Map<String, Object>> inbound(
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody InboundSms payload) {

        if (!authorized(signature)) {
            log.warn("Rejected partner-SMS webhook: bad signature");
            return ResponseEntity.status(401).body(Map.of(STATUS_KEY, "unauthorized"));
        }
        String body = payload != null ? payload.body() : null;
        Optional<PrescriptionRoutingDecision> result = exchangeService.handleInboundReply(body);
        return result
                .<ResponseEntity<Map<String, Object>>>map(d -> ResponseEntity.ok(Map.of(
                        STATUS_KEY, "applied",
                        "routingDecisionId", d.getId(),
                        "decisionStatus", d.getStatus().name())))
                .orElseGet(() -> ResponseEntity.ok(Map.of(STATUS_KEY, "ignored")));
    }

    private boolean authorized(String signature) {
        if (configuredSecret == null || configuredSecret.isBlank() || signature == null) {
            return false;
        }
        // Constant-time comparison to mitigate timing side-channels.
        byte[] expected = configuredSecret.getBytes(StandardCharsets.UTF_8);
        byte[] provided = signature.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }

    /** Minimal payload accepted from SMS gateways. */
    public record InboundSms(String from, String body) { }
}
