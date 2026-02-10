package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Set;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleBlueprintDTO {

    private String key;
    private String displayName;
    private String description;
    private Set<String> defaultPermissionCodes;
    private int permissionCount;
}
