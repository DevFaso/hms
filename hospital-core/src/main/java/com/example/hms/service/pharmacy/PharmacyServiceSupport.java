package com.example.hms.service.pharmacy;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Shared helpers for pharmacy services (current-user resolution and audit logging).
 * Extracted to avoid duplication between {@link DispenseServiceImpl} and
 * {@link StockOutRoutingServiceImpl}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class PharmacyServiceSupport {

    private final RoleValidator roleValidator;
    private final UserRepository userRepository;
    private final AuditEventLogService auditEventLogService;

    /**
     * Resolve the authenticated user, or throw if unavailable / not persisted.
     */
    User resolveCurrentUser() {
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException("Unable to determine current user");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user.current.notfound"));
    }

    /**
     * Record a SUCCESS audit event. Failures to write the audit log are swallowed
     * to avoid breaking the caller's business transaction.
     */
    void logAudit(AuditEventType eventType, String description, String resourceId, String entityType) {
        try {
            UUID userId = roleValidator.getCurrentUserId();
            auditEventLogService.logEvent(AuditEventRequestDTO.builder()
                    .userId(userId)
                    .eventType(eventType)
                    .eventDescription(description)
                    .status(AuditStatus.SUCCESS)
                    .resourceId(resourceId)
                    .entityType(entityType)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to log audit event {}: {}", eventType, e.getMessage());
        }
    }
}
