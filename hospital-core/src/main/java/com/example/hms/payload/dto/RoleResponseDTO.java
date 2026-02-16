package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleResponseDTO {

    private UUID id;
    private String name;
    private String authority;
    private String description;
    private Set<PermissionResponseDTO> permissions;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String code;
}


