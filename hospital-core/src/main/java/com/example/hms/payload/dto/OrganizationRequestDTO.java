package com.example.hms.payload.dto;

import com.example.hms.enums.OrganizationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationRequestDTO {

    @NotBlank(message = "Organization name is required")
    @Size(max = 255, message = "Organization name must not exceed 255 characters")
    private String name;

    @NotBlank(message = "Organization code is required")
    @Size(max = 100, message = "Organization code must not exceed 100 characters")
    private String code;

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;

    @NotNull(message = "Organization type is required")
    private OrganizationType type;

    @Builder.Default
    private boolean active = true;
}