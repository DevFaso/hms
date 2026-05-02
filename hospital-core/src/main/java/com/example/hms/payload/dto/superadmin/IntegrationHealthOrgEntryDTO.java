package com.example.hms.payload.dto.superadmin;

import com.example.hms.enums.integration.IntegrationHealthStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One snapshot row for a {@code (integration, organization)} pair as shown in
 * the super-admin Integration Health Console grid. {@code organizationId} is
 * null for platform-wide descriptors that have not yet been org-scoped.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationHealthOrgEntryDTO {

    private UUID organizationId;

    private String organizationName;

    private IntegrationHealthStatus status;

    private LocalDateTime lastSuccessAt;

    private LocalDateTime lastFailureAt;

    private String lastErrorMessage;

    private int successCount24h;

    private int failureCount24h;

    private LocalDateTime updatedAt;
}
