package com.example.hms.payload.dto.dashboard;

import java.util.List;
import java.util.UUID;

public record DashboardRoleConfigDTO(
    String roleCode,
    String roleName,
    UUID hospitalId,
    String hospitalName,
    List<String> permissions
) {
}
