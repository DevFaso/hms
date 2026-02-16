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

    @NotBlank(message = "Language code is required")
    private String languageCode;

    @NotBlank(message = "Translated name cannot be blank")
    private String name;

    private String description;

}
