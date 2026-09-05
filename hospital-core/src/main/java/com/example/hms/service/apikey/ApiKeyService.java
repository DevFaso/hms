package com.example.hms.service.apikey;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.platform.ApiKeyStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ApiKeyMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.platform.ApiKey;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.platform.ApiKeyCreateDTO;
import com.example.hms.payload.dto.platform.ApiKeyIssuedDTO;
import com.example.hms.payload.dto.platform.ApiKeyResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.platform.ApiKeyRepository;
import com.example.hms.security.SecurityUtils;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * API keys HMS issues to third-party clients (Tier 2 item 45).
 *
 * <p>Contract: the raw key is generated here, returned ONCE, and only its
 * SHA-256 hash is stored (the PasswordResetToken precedent). Keys are
 * hospital-scoped; a foreign or nonexistent key id collapses to the
 * identical not-found (the #550 oracle lesson). Revoke and rotate are
 * decide-once via {@code @Version} (the #549 lesson). Every lifecycle
 * change is audited — the trail a key-compromise investigation starts
 * from.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyService {

    /** What a verified presented key grants: identity, not staff roles. */
    public record ApiKeyAuth(UUID keyId, UUID hospitalId, String label) {
    }

    private static final String NOT_FOUND = "API key not found.";
    private static final String RAW_PREFIX = "hms_pk_";
    private static final int RANDOM_BYTES = 32;
    private static final int DISPLAY_PREFIX_CHARS = 12;
    /** lastUsedAt is a liveness signal — one write a minute is plenty. */
    private static final int LAST_USED_THROTTLE_SECONDS = 60;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;
    private final HospitalRepository hospitalRepository;
    private final RoleValidator roleValidator;
    private final AuditEventLogService auditService;
    private final ApiKeyMapper mapper;
    private final Clock clock;

    // ── lifecycle ───────────────────────────────────────────────────────

    @Transactional
    public ApiKeyIssuedDTO issue(ApiKeyCreateDTO request) {
        UUID hospitalId = requireHospital();
        if (request.getExpiresOn() != null
                && !request.getExpiresOn().isAfter(LocalDate.now(clock))) {
            throw new BusinessException("An expiry date must be in the future.");
        }
        Hospital hospital = hospitalRepository.getReferenceById(hospitalId);
        String rawKey = generateRawKey();
        ApiKey saved = apiKeyRepository.save(ApiKey.builder()
            .hospital(hospital)
            .label(request.getLabel().strip())
            .keyPrefix(rawKey.substring(0, DISPLAY_PREFIX_CHARS))
            .keyHash(sha256Hex(rawKey))
            .status(ApiKeyStatus.ACTIVE)
            .expiresOn(request.getExpiresOn())
            .build());
        log.info("API key {} issued at hospital {}", saved.getId(), hospitalId);
        emitAudit(AuditEventType.API_KEY_CREATED, saved, "API key issued");
        return ApiKeyIssuedDTO.builder()
            .key(mapper.toDto(saved))
            .rawKey(rawKey)
            .build();
    }

    /** Revokes the old key and issues a fresh one under the same label. */
    @Transactional
    public ApiKeyIssuedDTO rotate(UUID keyId) {
        UUID hospitalId = requireHospital();
        ApiKey old = requireActiveInTenant(keyId, hospitalId);
        close(old);
        emitAudit(AuditEventType.API_KEY_ROTATED, old, "API key rotated");

        String rawKey = generateRawKey();
        ApiKey replacement = apiKeyRepository.save(ApiKey.builder()
            .hospital(old.getHospital())
            .label(old.getLabel())
            .keyPrefix(rawKey.substring(0, DISPLAY_PREFIX_CHARS))
            .keyHash(sha256Hex(rawKey))
            .status(ApiKeyStatus.ACTIVE)
            .expiresOn(old.getExpiresOn())
            .build());
        log.info("API key {} rotated to {} at hospital {}",
            old.getId(), replacement.getId(), hospitalId);
        return ApiKeyIssuedDTO.builder()
            .key(mapper.toDto(replacement))
            .rawKey(rawKey)
            .build();
    }

    @Transactional
    public ApiKeyResponseDTO revoke(UUID keyId) {
        UUID hospitalId = requireHospital();
        ApiKey key = requireActiveInTenant(keyId, hospitalId);
        close(key);
        log.info("API key {} revoked at hospital {}", key.getId(), hospitalId);
        emitAudit(AuditEventType.API_KEY_REVOKED, key, "API key revoked");
        return mapper.toDto(key);
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponseDTO> list() {
        UUID hospitalId = requireHospital();
        return apiKeyRepository.findByHospital_IdOrderByCreatedAtDesc(hospitalId).stream()
            .map(mapper::toDto)
            .toList();
    }

    // ── verification (the filter's entry point) ─────────────────────────

    /**
     * Verifies a presented raw key: hash lookup, active status, expiry.
     * Returns empty — never a reason — on any failure: a caller probing
     * with candidate keys learns nothing about WHY one was refused.
     */
    @Transactional
    public Optional<ApiKeyAuth> authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        Optional<ApiKey> found = apiKeyRepository
            .findByKeyHashAndStatus(sha256Hex(rawKey.strip()), ApiKeyStatus.ACTIVE);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiKey key = found.get();
        if (key.getExpiresOn() != null && key.getExpiresOn().isBefore(LocalDate.now(clock))) {
            return Optional.empty();
        }
        touchLastUsed(key);
        return Optional.of(new ApiKeyAuth(key.getId(), key.getHospital().getId(), key.getLabel()));
    }

    /** Throttled and best-effort — a bookkeeping write must never fail a request. */
    private void touchLastUsed(ApiKey key) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (key.getLastUsedAt() != null
                && key.getLastUsedAt().isAfter(now.minusSeconds(LAST_USED_THROTTLE_SECONDS))) {
            return;
        }
        try {
            key.setLastUsedAt(now);
            apiKeyRepository.save(key);
        } catch (RuntimeException ex) {
            log.warn("Failed to stamp lastUsedAt on API key {}: {}", key.getId(), ex.getMessage());
        }
    }

    // ── guards ──────────────────────────────────────────────────────────

    private UUID requireHospital() {
        UUID hospitalId = roleValidator.requireActiveHospitalId();
        if (hospitalId == null) {
            throw new BusinessException("An active hospital context is required.");
        }
        return hospitalId;
    }

    /** Foreign, nonexistent and already-revoked ids collapse or refuse cleanly. */
    private ApiKey requireActiveInTenant(UUID keyId, UUID hospitalId) {
        ApiKey key = apiKeyRepository.findById(keyId)
            .filter(k -> k.getHospital() != null && hospitalId.equals(k.getHospital().getId()))
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        if (key.getStatus() != ApiKeyStatus.ACTIVE) {
            throw new BusinessException("The key is already revoked.");
        }
        return key;
    }

    private void close(ApiKey key) {
        key.setStatus(ApiKeyStatus.REVOKED);
        key.setRevokedAt(LocalDateTime.now(clock));
        try {
            // Flush inside the method so a concurrent revoke/rotate pair
            // surfaces as the @Version conflict HERE (the #549 lesson).
            apiKeyRepository.saveAndFlush(key);
        } catch (OptimisticLockingFailureException e) {
            throw new BusinessException(
                "The key was changed at the same time by someone else - reload and retry.");
        }
    }

    // ── key material ────────────────────────────────────────────────────

    private static String generateRawKey() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return RAW_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Best-effort: an audit failure must never undo a credential change. */
    private void emitAudit(AuditEventType type, ApiKey key, String description) {
        try {
            auditService.logEvent(AuditEventRequestDTO.builder()
                .eventType(type)
                .status(AuditStatus.SUCCESS)
                .entityType("API_KEY")
                .resourceId(key.getId() != null ? key.getId().toString() : null)
                .userId(roleValidator.getCurrentUserId())
                .userName(SecurityUtils.getCurrentUsername())
                .hospitalName(key.getHospital() != null ? key.getHospital().getName() : null)
                .eventDescription(description + " (" + key.getKeyPrefix() + "…)")
                .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to emit {} audit for API key {}: {}",
                type, key.getId(), ex.getMessage());
        }
    }
}
