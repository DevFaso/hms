package com.example.hms.payload.dto;

import com.example.hms.enums.AdmissionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request DTO for creating/updating admission order set templates
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdmissionOrderSetRequestDTO {

    @NotBlank(message = "{admissionOrderSet.name.required}")
    private String name;

    private String description;

    @NotNull(message = "{admissionOrderSet.admissionType.required}")
    private AdmissionType admissionType;

    private UUID departmentId;

    @NotNull(message = "{department.hospital.required}")
    private UUID hospitalId;

    @NotNull(message = "{admissionOrderSet.orderItems.required}")
    private List<Map<String, Object>> orderItems;

    private String clinicalGuidelines;

    private Boolean active = true;

    @NotNull(message = "{admissionOrderSet.createdByStaffId.required}")
    private UUID createdByStaffId;
}
