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

    // QA D1349 (round 30): the create handler validates the OnCreate group only, so
    // these three are named at the edge with a malformed value (the other
    // constraints here stay unapplied, as before: see RequiredRequestIdGuardTest).
    @NotBlank(groups = com.example.hms.validation.OnCreate.class, message = "{staff.user.required}")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "User email linked to this staff profile")
    private String userEmail;

    @NotBlank(groups = com.example.hms.validation.OnCreate.class, message = "{staff.hospital.required}")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Hospital name where staff is registered")
    private String hospitalName;

    @Schema(description = "Department name (optional)")
    private String departmentName;

    @NotBlank(groups = com.example.hms.validation.OnCreate.class, message = "{staff.jobTitle.required}")
    @Size(max = 100, message = "{staff.jobTitle.size}")
    // #1096 review: an unknown title is named at the edge with the other refused values (D1371).
    @com.example.hms.validation.KnownJobTitle(groups = {com.example.hms.validation.OnCreate.class,
        jakarta.validation.groups.Default.class}, message = "{staff.jobTitle.invalid}")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Job title for the staff")
    private String jobTitle;

    // Round 30: @NotNull, not @NotBlank (an enum: HV000030 when validated). Default group only,
    // which no handler applies to this body (create validates OnCreate, update nothing).
    @jakarta.validation.constraints.NotNull(message = "{staff.employmentType.blank}")
    @Schema(description = "Employment type, e.g. FULL_TIME, PART_TIME")
    @Enumerated(EnumType.STRING)
    private EmploymentType employmentType;

    // Round 30: the @Size it carried cannot apply to an enum (HV000030); removed.
    @Enumerated(EnumType.STRING)
    @Schema(description = "Specialization of the staff (e.g. CARDIOLOGY, PEDIATRICS) — required for doctors/radiologists only")
    private Specialization specialization;

    @Size(max = 50, message = "{staff.license.size}")
    @Schema(description = "License number (required for roles like doctor, nurse, pharmacist)")
    private String licenseNumber;

    // #948 review: the portal defaults this to the reader's today (east of UTC,
    // already tomorrow on the server's calendar), so the check allows that day.
    @com.example.hms.validation.PickedDayNotInFuture(message = "{staff.startDate.past}")
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
