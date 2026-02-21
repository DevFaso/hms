package com.example.hms.payload.dto;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.Specialization;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "StaffRequestDTO", description = "Payload to create or update a staff member")
public class StaffRequestDTO {

    @NotBlank(message = "{staff.user.required}")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "User email linked to this staff profile")
    private String userEmail;

    @NotBlank(message = "{staff.hospital.required}")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Hospital name where staff is registered")
    private String hospitalName;

    @Schema(description = "Department name (optional)")
    private String departmentName;

    @NotBlank(message = "{staff.jobTitle.blank}")
    @Size(max = 100, message = "{staff.jobTitle.size}")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Job title for the staff")
    private String jobTitle;

    @NotBlank(message = "{staff.employmentType.blank}")
    @Schema(description = "Employment type, e.g. FULL_TIME, PART_TIME")
    @Enumerated(EnumType.STRING)
    private EmploymentType employmentType;

    @Size(max = 100, message = "{staff.specialization.size}")
    @Enumerated(EnumType.STRING)
    @Schema(description = "Specialization of the staff (e.g. CARDIOLOGY, PEDIATRICS) — required for doctors/radiologists only")
    private Specialization specialization;

    @Size(max = 50, message = "{staff.license.size}")
    @Schema(description = "License number (required for roles like doctor, nurse, pharmacist)")
    private String licenseNumber;

    @PastOrPresent(message = "{staff.startDate.past}")
    @Schema(description = "Employment start date")
    private LocalDate startDate;

    @FutureOrPresent(message = "{staff.endDate.future}")
    @Schema(description = "Employment end date (if applicable)")
    private LocalDate endDate;

    @Builder.Default
    @Schema(description = "Whether staff is active (defaults to true)")
    private boolean active = true;

    @Builder.Default
    @JsonProperty("isHeadOfDepartment")
    @Schema(description = "Flag if this staff is head of department")
    private Boolean headOfDepartment = false;

    @Schema(description = "Role to assign (provide roleName)")
    private String roleName;
}
