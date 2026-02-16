package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEventLogResponseDTO {

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
}
