package com.example.hms.payload.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Builder
@Data
public class PermissionResponseDTO {
    private UUID id;
    private String name;
    private String code;

    private UUID assignmentId;
    private String assignmentName;
    private String assignmentType;
    private String assignmentDescription;

}
