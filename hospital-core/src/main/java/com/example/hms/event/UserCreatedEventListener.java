package com.example.hms.event;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.service.AuditEventLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Records the USER_CREATE audit log entry <em>after</em> the outer
 * transaction that created the user has committed.
 *
 * <p>Running after-commit guarantees that the new user row is visible in
 * the database when {@link AuditEventLogService#logEvent} (which uses
 * {@code REQUIRES_NEW}) tries to resolve the user by ID.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserCreatedEventListener {

    private final AuditEventLogService auditEventLogService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserCreated(UserCreatedEvent event) {
        try {
            Map<String, Object> details = new HashMap<>();
            details.put("createdUserId", event.getCreatedUserId());
            details.put("actorId", event.getActorId());
            details.put("assignmentIds", event.getAssignmentIds());

            AuditEventRequestDTO auditEvent = AuditEventRequestDTO.builder()
                    .userId(event.getCreatedUserId())
                    .assignmentId(event.getAssignmentIds().isEmpty()
                            ? null
                            : event.getAssignmentIds().get(0))
                    .userName(event.getCreatedUserDisplayName())
                    .eventType(AuditEventType.USER_CREATE)
                    .eventDescription("New user account created")
                    .resourceId(event.getCreatedUserId().toString())
                    .resourceName(event.getCreatedUserDisplayName())
                    .entityType("USER")
                    .status(AuditStatus.SUCCESS)
                    .details(details)
                    .build();

            auditEventLogService.logEvent(auditEvent);
        } catch (RuntimeException ex) {
            // Audit failure must never affect the already-committed user creation
            log.warn("⚠️ Failed to record USER_CREATE audit for user '{}': {}",
                    event.getCreatedUserId(), ex.getMessage());
        }
    }
}
