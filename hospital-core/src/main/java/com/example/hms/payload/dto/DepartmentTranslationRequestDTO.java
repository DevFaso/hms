package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepartmentTranslationRequestDTO {

    private UUID departmentId;

    @NotBlank(message = "{departmentTranslation.languageCode.required}")
    private String languageCode;

    @NotBlank(message = "{departmentTranslation.name.required}")
    private String name;

    private String description;

}
