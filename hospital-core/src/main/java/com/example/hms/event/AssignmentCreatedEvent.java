package com.example.hms.event;

import lombok.Getter;

import java.util.UUID;

/**
 * Published after a {@code UserRoleHospitalAssignment} is saved so that
 * the email + SMS notifications are dispatched <em>only after</em> the
 * outer transaction commits.
 *
 * <p>Carrying raw scalar values (no JPA entities) keeps the payload
 * serialisable and decoupled from the persistence context.</p>
 */
@Getter
public class AssignmentCreatedEvent {

    /** The persisted assignment's PK — used to re-load it in the listener. */
    private final UUID assignmentId;

    /**
     * True when the new user's welcome mail already carries this
     * assignment's confirmation code: the listener then skips the separate
     * assignment e-mail (SMS still goes out).
     */
    private final boolean emailCoveredByWelcome;

    public AssignmentCreatedEvent(UUID assignmentId) {
        this(assignmentId, false);
    }

    public AssignmentCreatedEvent(UUID assignmentId, boolean emailCoveredByWelcome) {
        this.assignmentId = assignmentId;
        this.emailCoveredByWelcome = emailCoveredByWelcome;
    }
}
