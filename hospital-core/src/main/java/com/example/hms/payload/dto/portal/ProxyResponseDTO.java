package com.example.hms.payload.dto.portal;

import com.example.hms.enums.ProxyRelationship;
import com.example.hms.enums.ProxyStatus;
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
public class ProxyResponseDTO {
    private UUID id;
    private UUID grantorPatientId;
    private String grantorName;
    private UUID proxyUserId;
    private String proxyUsername;
    private String proxyDisplayName;
    private ProxyRelationship relationship;
    private ProxyStatus status;
    private String permissions;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private String notes;
    private LocalDateTime createdAt;
}
