package com.example.hms.payload.dto.superadmin;

import java.util.UUID;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SuperAdminCreateOrganizationResponseDTO {
    UUID id;
    String code;
    String name;
    String message;
}
