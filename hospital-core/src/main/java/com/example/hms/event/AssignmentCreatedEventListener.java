package com.example.hms.event;

import com.example.hms.service.UserRoleHospitalAssignmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the assignment confirmation email + SMS <em>only after</em> the outer
 * transaction has committed successfully.
 *
 * <p>This prevents the "ghost link" problem: without this listener, email and SMS
 * are dispatched inside the outer transaction — if that transaction later rolls
 * back (e.g. due to a validation error), the recipient receives a link with an
 * assignment code that no longer exists in the database.</p>
 *
 * <p>{@link UserRoleHospitalAssignmentService#sendNotifications(java.util.UUID)}
 * runs in its own {@code REQUIRES_NEW} transaction so that a notification failure
 * never affects the already-committed data.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssignmentCreatedEventListener {

    private final UserRoleHospitalAssignmentService assignmentService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAssignmentCreated(AssignmentCreatedEvent event) {
        log.debug("[ASSIGNMENT_NOTIFY] Sending notifications for committed assignment {}",
                event.getAssignmentId());
        try {
            assignmentService.sendNotifications(event.getAssignmentId());
        } catch (RuntimeException ex) {
            // Log but do not rethrow — the outer transaction already committed.
            log.warn("[ASSIGNMENT_NOTIFY] ⚠️ Failed to send notifications for assignment {}: {}",
                    event.getAssignmentId(), ex.getMessage());
        }
    }
}
