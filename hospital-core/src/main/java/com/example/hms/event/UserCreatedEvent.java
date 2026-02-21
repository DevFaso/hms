package com.example.hms.event;

import lombok.Getter;

import java.util.List;
import java.util.UUID;

/**
 * Published after a new user is saved so the audit log can be recorded
 * <em>after</em> the outer transaction commits — ensuring the new user
 * row is visible to the audit service's own (REQUIRES_NEW) transaction.
 */
@Getter
public class UserCreatedEvent {

    private final UUID actorId;
    private final String actorDisplayName;
    private final UUID createdUserId;
    private final String createdUserDisplayName;
    private final List<UUID> assignmentIds;

    public UserCreatedEvent(UUID actorId, String actorDisplayName,
                            UUID createdUserId, String createdUserDisplayName,
                            List<UUID> assignmentIds) {
        this.actorId = actorId;
        this.actorDisplayName = actorDisplayName;
        this.createdUserId = createdUserId;
        this.createdUserDisplayName = createdUserDisplayName;
        this.assignmentIds = List.copyOf(assignmentIds);
    }
}
