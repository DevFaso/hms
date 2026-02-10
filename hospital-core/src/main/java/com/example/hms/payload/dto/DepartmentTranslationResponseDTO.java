package com.example.hms.payload.dto;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepartmentTranslationResponseDTO {

    private UUID id;
    private UUID departmentId;
    private String languageCode;
    private String name;
    private String description;
}
