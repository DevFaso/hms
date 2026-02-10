package com.example.hms.payload.dto.superadmin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformRegistrySnapshotDTO {
    private LocalDateTime generatedAt;
    private SuperAdminPlatformRegistrySummaryDTO summary;
}
