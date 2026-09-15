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

    @NotBlank(message = "{organization.name.required}")
    @Size(max = 255, message = "{organization.name.size}")
    private String name;

    @NotBlank(message = "{organization.code.required}")
    @Size(max = 100, message = "{organization.code.size}")
    private String code;

    @Size(max = 500, message = "{organization.description.size}")
    private String description;

    @NotNull(message = "{organization.type.required}")
    private OrganizationType type;

    @Builder.Default
    private boolean active = true;
}