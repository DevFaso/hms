package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEventLogResponseDTO {

    /** AuditEventLog primary key. Exposed so the cross-tenant audit search
     * UI (MVP-8) can track table rows by a guaranteed-unique value rather
     * than a fingerprint composed of nullable fields (PR #228 review). */
    private UUID id;

    private String userName;
    private String hospitalName;
    private String roleName;
    private String eventType;
    private String eventDescription;
    private String details;
    private LocalDateTime eventTimestamp;
    private String ipAddress;
    private String status;
    private String resourceId;
    private String resourceName;
    private String entityType;
    private String actorType;
    private String actorLabel;
    private UUID impersonatorUserId;
    private String impersonatorUsername;
}
