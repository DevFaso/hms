package com.example.hms.payload.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class DepartmentRequestDTO {

    private String id;

    private UUID hospitalId;

    private String hospitalName;

    @NotBlank(message = "{department.name.required}")
    @Size(min = 2, max = 100, message = "{department.name.size}")
    @Pattern(regexp = "^[a-zA-Z0-9 \\-]+$", message = "{department.name.pattern}")
    private String name;

    @Size(max = 500, message = "{department.description.size}")
    private String description;

    private String headOfDepartmentEmail;

    @Size(max = 20, message = "{department.phone.size}")
    @Pattern(regexp = "^[\\d\\s\\-()+]*$", message = "{department.phone.pattern}")
    private String phoneNumber;

    @Email(message = "{department.email.invalid}")
    @Size(max = 100, message = "{department.email.size}")
    private String email;

    @Builder.Default
    private boolean active = true;

    @Valid
    private List<DepartmentTranslationRequestDTO> translations;

    // Use 'code' for department code to match entity and payload
    @NotBlank(message = "{department.code.required}")
    @Size(max = 32, message = "{department.code.size}")
    @Pattern(regexp = "^[A-Z0-9_\\-]+$", message = "{department.code.pattern}")
    private String code;

    private Integer floorNumber;
    private String wing;
    private Integer bedCapacity;

    // Emergency contact information
    @Size(max = 20)
    private String emergencyPhone;

    // Operational hours
    private String operatingHours;
}

