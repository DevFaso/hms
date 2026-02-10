package com.example.hms.service;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.FrontendAuditEvent;
import com.example.hms.payload.dto.FrontendAuditEventRequestDTO;
import com.example.hms.repository.FrontendAuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FrontendAuditServiceImpl implements FrontendAuditService {

    private final FrontendAuditEventRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void recordEvent(FrontendAuditEventRequestDTO request, String ipAddress, String userAgent) {
        if (request == null) {
            throw new BusinessException("Audit payload is required.");
        }
        String normalizedType = trimToNull(request.getType());
        if (normalizedType == null) {
            throw new BusinessException("Event type is required.");
        }
        LocalDateTime occurredAt = parseTimestamp(request.getTs());
        String metadata = serializeMeta(request.getMeta());
        String resolvedUserAgent = firstNonBlank(userAgent, request.getUserAgent());
        String resolvedIp = firstNonBlank(ipAddress, request.getIpAddress());

        FrontendAuditEvent entity = FrontendAuditEvent.builder()
            .eventType(normalizedType.toUpperCase())
            .actor(trimToNull(request.getActor()))
            .metadata(metadata)
            .occurredAt(occurredAt)
            .userAgent(trimToNull(resolvedUserAgent))
            .ipAddress(trimToNull(resolvedIp))
            .build();
        repository.save(entity);
    }

    private String serializeMeta(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(meta);
            if (json.length() > 3900) {
                log.debug("Frontend audit metadata truncated from {} characters", json.length());
                return json.substring(0, 3900);
            }
            return json;
        } catch (JsonProcessingException e) {
            log.warn("Unable to serialize frontend audit metadata", e);
            return null;
        }
    }

    private LocalDateTime parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return OffsetDateTime.parse(raw).toLocalDateTime();
        } catch (DateTimeParseException first) {
            try {
                return LocalDateTime.parse(raw);
            } catch (DateTimeParseException ignored) {
                log.debug("Unable to parse frontend audit timestamp '{}', defaulting to now", raw);
                return LocalDateTime.now();
            }
        }
    }

    private String firstNonBlank(String primary, String secondary) {
        String first = trimToNull(primary);
        if (first != null) {
            return first;
        }
        return trimToNull(secondary);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
