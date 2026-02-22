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

    public AssignmentCreatedEvent(UUID assignmentId) {
        this.assignmentId = assignmentId;
    }
}
