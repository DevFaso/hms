package com.example.hms.service.webhook;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.platform.WebhookEndpointStatus;
import com.example.hms.enums.platform.WebhookEventType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.WebhookMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.platform.WebhookDelivery;
import com.example.hms.model.platform.WebhookEndpoint;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.platform.WebhookDeliveryResponseDTO;
import com.example.hms.payload.dto.platform.WebhookEndpointRegisteredDTO;
import com.example.hms.payload.dto.platform.WebhookEndpointRequestDTO;
import com.example.hms.payload.dto.platform.WebhookEndpointResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.platform.WebhookDeliveryRepository;
import com.example.hms.repository.platform.WebhookEndpointRepository;
import com.example.hms.security.SecurityUtils;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Outbound webhook endpoints (Tier 2 item 45). House contract: hospital
 * scope via {@link RoleValidator}; foreign/nonexistent ids collapse to
 * the identical not-found (the #550 oracle lesson); revoked, never
 * deleted; the signing secret is generated server-side, encrypted at
 * rest, and returned exactly once. URLs pass the SSRF gate on every
 * write.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookEndpointService {

    static final int MAX_DELIVERY_PAGE = 200;

    private static final String NOT_FOUND = "Webhook endpoint not found.";
    private static final String SECRET_PREFIX = "whsec_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final HospitalRepository hospitalRepository;
    private final RoleValidator roleValidator;
    private final AuditEventLogService auditService;
    private final WebhookMapper mapper;

    // ── lifecycle ───────────────────────────────────────────────────────

    @Transactional
    public WebhookEndpointRegisteredDTO register(WebhookEndpointRequestDTO request) {
        UUID hospitalId = requireHospital();
        requireSubscribable(request);
        WebhookUrlValidator.requireDeliverable(request.getUrl());
        Hospital hospital = hospitalRepository.getReferenceById(hospitalId);
        String secret = generateSecret();
        WebhookEndpoint saved = endpointRepository.save(WebhookEndpoint.builder()
            .hospital(hospital)
            .url(request.getUrl().strip())
            .description(trimToNull(request.getDescription()))
            .secret(secret)
            .status(WebhookEndpointStatus.ACTIVE)
            .subscribedEvents(EnumSet.copyOf(request.getEvents()))
            .build());
        log.info("Webhook endpoint {} registered at hospital {}", saved.getId(), hospitalId);
        emitAudit(AuditEventType.WEBHOOK_ENDPOINT_REGISTERED, saved, "Webhook endpoint registered");
        return WebhookEndpointRegisteredDTO.builder()
            .endpoint(mapper.toDto(saved))
            .secret(secret)
            .build();
    }

    @Transactional
    public WebhookEndpointResponseDTO update(UUID endpointId, WebhookEndpointRequestDTO request) {
        UUID hospitalId = requireHospital();
        WebhookEndpoint endpoint = requireInTenant(endpointId, hospitalId);
        requireNotRevoked(endpoint);
        requireSubscribable(request);
        WebhookUrlValidator.requireDeliverable(request.getUrl());
        endpoint.setUrl(request.getUrl().strip());
        endpoint.setDescription(trimToNull(request.getDescription()));
        endpoint.getSubscribedEvents().clear();
        endpoint.getSubscribedEvents().addAll(request.getEvents());
        save(endpoint);
        emitAudit(AuditEventType.WEBHOOK_ENDPOINT_UPDATED, endpoint, "Webhook endpoint updated");
        return mapper.toDto(endpoint);
    }

    /** PAUSED ⇄ ACTIVE; also the admin's way back from DISABLED_FAILURES. */
    @Transactional
    public WebhookEndpointResponseDTO setActive(UUID endpointId, boolean active) {
        UUID hospitalId = requireHospital();
        WebhookEndpoint endpoint = requireInTenant(endpointId, hospitalId);
        requireNotRevoked(endpoint);
        if (active) {
            endpoint.setStatus(WebhookEndpointStatus.ACTIVE);
            // A deliberate re-enable wipes the strike count.
            endpoint.setConsecutiveFailures(0);
        } else {
            endpoint.setStatus(WebhookEndpointStatus.PAUSED);
        }
        save(endpoint);
        emitAudit(AuditEventType.WEBHOOK_ENDPOINT_UPDATED, endpoint,
            active ? "Webhook endpoint resumed" : "Webhook endpoint paused");
        return mapper.toDto(endpoint);
    }

    @Transactional
    public WebhookEndpointResponseDTO revoke(UUID endpointId) {
        UUID hospitalId = requireHospital();
        WebhookEndpoint endpoint = requireInTenant(endpointId, hospitalId);
        requireNotRevoked(endpoint);
        endpoint.setStatus(WebhookEndpointStatus.REVOKED);
        save(endpoint);
        emitAudit(AuditEventType.WEBHOOK_ENDPOINT_UPDATED, endpoint, "Webhook endpoint revoked");
        return mapper.toDto(endpoint);
    }

    /** New signing secret, returned once — the old one stops verifying immediately. */
    @Transactional
    public WebhookEndpointRegisteredDTO rotateSecret(UUID endpointId) {
        UUID hospitalId = requireHospital();
        WebhookEndpoint endpoint = requireInTenant(endpointId, hospitalId);
        requireNotRevoked(endpoint);
        String secret = generateSecret();
        endpoint.setSecret(secret);
        save(endpoint);
        emitAudit(AuditEventType.WEBHOOK_ENDPOINT_UPDATED, endpoint,
            "Webhook signing secret rotated");
        return WebhookEndpointRegisteredDTO.builder()
            .endpoint(mapper.toDto(endpoint))
            .secret(secret)
            .build();
    }

    /** Enqueues a PING delivery so an admin can prove the wiring end to end. */
    @Transactional
    public WebhookDeliveryResponseDTO ping(UUID endpointId) {
        UUID hospitalId = requireHospital();
        WebhookEndpoint endpoint = requireInTenant(endpointId, hospitalId);
        if (endpoint.getStatus() != WebhookEndpointStatus.ACTIVE) {
            throw new BusinessException("Only an ACTIVE endpoint can be pinged.");
        }
        WebhookDelivery delivery = deliveryRepository.save(WebhookDelivery.builder()
            .endpoint(endpoint)
            .eventType(WebhookEventType.PING)
            .payload(WebhookPublisher.pingPayload(hospitalId))
            .build());
        return mapper.toDto(delivery);
    }

    // ── reads ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WebhookEndpointResponseDTO> list() {
        UUID hospitalId = requireHospital();
        return endpointRepository.findByHospital_IdOrderByCreatedAtDesc(hospitalId).stream()
            .map(mapper::toDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public Page<WebhookDeliveryResponseDTO> deliveries(UUID endpointId, int page, int size) {
        UUID hospitalId = requireHospital();
        requireInTenant(endpointId, hospitalId);
        int boundedSize = Math.clamp(size, 1, MAX_DELIVERY_PAGE);
        return deliveryRepository.findByEndpoint_IdOrderByCreatedAtDesc(
                endpointId, PageRequest.of(Math.max(page, 0), boundedSize))
            .map(mapper::toDto);
    }

    /**
     * The partner surface's read: scope comes from the VERIFIED API key,
     * not from a staff hospital context — a partner has none.
     */
    @Transactional(readOnly = true)
    public Page<WebhookDeliveryResponseDTO> deliveriesForHospital(UUID hospitalId,
                                                                  int page, int size) {
        int boundedSize = Math.clamp(size, 1, MAX_DELIVERY_PAGE);
        return deliveryRepository.findByEndpoint_Hospital_IdOrderByCreatedAtDesc(
                hospitalId, PageRequest.of(Math.max(page, 0), boundedSize))
            .map(mapper::toDto);
    }

    // ── guards ──────────────────────────────────────────────────────────

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException("An active hospital context is required.");
        }
        return hospitalId;
    }

    private WebhookEndpoint requireInTenant(UUID endpointId, UUID hospitalId) {
        return endpointRepository.findById(endpointId)
            .filter(e -> e.getHospital() != null && hospitalId.equals(e.getHospital().getId()))
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    /**
     * PING is fired from the test button and bypasses subscriptions — a
     * PING-only "subscription" would be a valid-looking configuration
     * that can never receive an event.
     */
    private static void requireSubscribable(WebhookEndpointRequestDTO request) {
        if (request.getEvents().contains(WebhookEventType.PING)) {
            throw new BusinessException(
                "PING is the test delivery, not a subscribable event - use the ping action.");
        }
    }

    private static void requireNotRevoked(WebhookEndpoint endpoint) {
        if (endpoint.getStatus() == WebhookEndpointStatus.REVOKED) {
            throw new BusinessException("The endpoint is revoked.");
        }
    }

    private void save(WebhookEndpoint endpoint) {
        try {
            // Flush inside the method (the #549 lesson): concurrent edits
            // surface as a clean retryable refusal, not a silent overwrite.
            endpointRepository.saveAndFlush(endpoint);
        } catch (OptimisticLockingFailureException e) {
            throw new BusinessException(
                "The endpoint was changed at the same time by someone else - reload and retry.");
        }
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Best-effort: an audit failure must never undo a configuration change. */
    private void emitAudit(AuditEventType type, WebhookEndpoint endpoint, String description) {
        try {
            auditService.logEvent(AuditEventRequestDTO.builder()
                .eventType(type)
                .status(AuditStatus.SUCCESS)
                .entityType("WEBHOOK_ENDPOINT")
                .resourceId(endpoint.getId() != null ? endpoint.getId().toString() : null)
                .userId(roleValidator.getCurrentUserId())
                .userName(SecurityUtils.getCurrentUsername())
                .hospitalName(endpoint.getHospital() != null
                    ? endpoint.getHospital().getName() : null)
                .eventDescription(description)
                .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to emit {} audit for webhook endpoint {}: {}",
                type, endpoint.getId(), ex.getMessage());
        }
    }
}
