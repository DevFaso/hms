package com.example.hms.payload.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * A care team member that the patient can message directly.
 * Returned by {@code GET /me/patient/care-team/messageable}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CareTeamContactDTO {

    /** The User ID used as the chat recipient (matches ChatMessage.recipient). */
    private UUID userId;

    /** Display name: "Dr. Jane Smith" or staff name. */
    private String displayName;

    /** Human-readable role label: "Primary Care Provider" or "Previous Provider". */
    private String roleLabel;
}
