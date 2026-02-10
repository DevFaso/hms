package com.example.hms.payload.dto.dashboard;

import java.util.List;
import java.util.UUID;

public record DashboardConfigResponseDTO(
    UUID userId,
    String primaryRoleCode,
    List<DashboardRoleConfigDTO> roles,
    List<String> mergedPermissions
) {
}
